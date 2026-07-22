package com.luminex_studios.luminstatus.incident;

import com.luminex_studios.luminstatus.model.ComponentState;
import com.luminex_studios.luminstatus.model.Incident;
import com.luminex_studios.luminstatus.util.Json;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.luminex_studios.luminstatus.history.HistoryStore.writeAtomically;

/** Creates, updates and persists operator-authored incidents. */
public final class IncidentManager {

    private final Path file;
    private final Logger logger;
    private final int retentionDays;
    private final ConcurrentHashMap<String, Incident> incidents = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public IncidentManager(Path dataDirectory, Logger logger, int retentionDays) {
        this.file = dataDirectory.resolve("incidents.json");
        this.logger = logger;
        this.retentionDays = retentionDays;
    }

    public Incident create(String title, ComponentState impact, Set<String> componentIds, String firstUpdate) {
        String id = nextId();
        Incident incident = new Incident(id, title, impact, new LinkedHashSet<>(componentIds), Instant.now());
        incident.addUpdate(Incident.Stage.INVESTIGATING,
                firstUpdate == null || firstUpdate.isBlank() ? "We are looking into this." : firstUpdate);
        incidents.put(id, incident);
        save();
        return incident;
    }

    public Optional<Incident> find(String id) {
        return Optional.ofNullable(incidents.get(id.toLowerCase(Locale.ROOT)));
    }

    public Optional<Incident> update(String id, Incident.Stage stage, String message) {
        Optional<Incident> found = find(id);
        found.ifPresent(incident -> {
            incident.addUpdate(stage, message);
            save();
        });
        return found;
    }

    /** Incidents that are still open, newest first. */
    public List<Incident> active() {
        return incidents.values().stream()
                .filter(incident -> !incident.resolved())
                .sorted(Comparator.comparing(Incident::createdAt).reversed())
                .toList();
    }

    /** Resolved incidents inside the retention window, newest first. */
    public List<Incident> recentlyResolved(int limit) {
        return incidents.values().stream()
                .filter(Incident::resolved)
                .sorted(Comparator.comparing(Incident::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    public List<Incident> all() {
        return incidents.values().stream()
                .sorted(Comparator.comparing(Incident::createdAt).reversed())
                .toList();
    }

    /** The worst impact declared by any open incident touching this component. */
    public ComponentState declaredImpact(String componentId) {
        ComponentState worst = ComponentState.OPERATIONAL;
        for (Incident incident : active()) {
            if (incident.components().contains(componentId)) {
                worst = ComponentState.worst(worst, incident.impact());
            }
        }
        return worst;
    }

    public void prune() {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86400L);
        incidents.values().removeIf(incident ->
                incident.resolved() && incident.resolvedAt() != null && incident.resolvedAt().isBefore(cutoff));
    }

    private String nextId() {
        String prefix = Instant.now().toString().substring(0, 10).replace("-", "");
        String id;
        do {
            id = prefix + "-" + String.format(Locale.ROOT, "%03d", sequence.incrementAndGet());
        } while (incidents.containsKey(id));
        return id;
    }

    public void load() {
        if (Files.notExists(file)) {
            return;
        }
        try {
            Object parsed = Json.read(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Json.Arr array)) {
                logger.warn("incidents.json is not a JSON array, starting with no incidents");
                return;
            }
            int highest = 0;
            for (Object element : array.values()) {
                if (!(element instanceof Json.Obj node)) {
                    continue;
                }
                Set<String> components = new LinkedHashSet<>();
                Json.Arr componentArray = node.arr("components");
                if (componentArray != null) {
                    for (Object component : componentArray.values()) {
                        components.add(String.valueOf(component));
                    }
                }
                String id = node.string("id", nextId());
                Incident incident = new Incident(
                        id,
                        node.string("title", "Incident"),
                        parseImpact(node.string("impact", "major_outage")),
                        components,
                        Instant.ofEpochSecond(node.number("created_at", Instant.now().getEpochSecond()))
                );
                List<Incident.Update> updates = new ArrayList<>();
                Json.Arr updateArray = node.arr("updates");
                if (updateArray != null) {
                    for (Object element2 : updateArray.values()) {
                        if (element2 instanceof Json.Obj updateNode) {
                            updates.add(new Incident.Update(
                                    Instant.ofEpochSecond(updateNode.number("at", 0)),
                                    Incident.Stage.parse(updateNode.string("stage", "investigating")),
                                    updateNode.string("message", "")
                            ));
                        }
                    }
                }
                long resolvedAt = node.number("resolved_at", 0);
                incident.restore(
                        Incident.Stage.parse(node.string("stage", "investigating")),
                        resolvedAt == 0 ? null : Instant.ofEpochSecond(resolvedAt),
                        updates
                );
                incidents.put(id, incident);
                int suffix = suffixOf(id);
                highest = Math.max(highest, suffix);
            }
            sequence.set(highest);
            prune();
        } catch (IOException | RuntimeException ex) {
            logger.warn("Could not read incidents.json, starting with no incidents", ex);
        }
    }

    private static int suffixOf(String id) {
        int dash = id.lastIndexOf('-');
        if (dash < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(dash + 1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static ComponentState parseImpact(String raw) {
        for (ComponentState state : ComponentState.values()) {
            if (state.id().equals(raw)) {
                return state;
            }
        }
        return ComponentState.MAJOR_OUTAGE;
    }

    public void save() {
        Json.Arr array = Json.arr();
        for (Incident incident : all()) {
            Json.Arr components = Json.arr();
            incident.components().forEach(components::add);
            Json.Arr updates = Json.arr();
            for (Incident.Update update : incident.updates()) {
                updates.add(Json.obj()
                        .put("at", update.at().getEpochSecond())
                        .put("stage", update.stage().id())
                        .put("message", update.message()));
            }
            array.add(Json.obj()
                    .put("id", incident.id())
                    .put("title", incident.title())
                    .put("impact", incident.impact().id())
                    .put("stage", incident.stage().id())
                    .put("components", components)
                    .put("created_at", incident.createdAt().getEpochSecond())
                    .put("resolved_at", incident.resolvedAt() == null ? 0 : incident.resolvedAt().getEpochSecond())
                    .put("updates", updates));
        }
        writeAtomically(file, Json.write(array), logger);
    }
}
