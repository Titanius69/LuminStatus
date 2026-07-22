package com.luminex_studios.luminstatus.web;

import com.luminex_studios.luminstatus.config.StatusConfig;
import com.luminex_studios.luminstatus.health.HealthMonitor;
import com.luminex_studios.luminstatus.history.DayRecord;
import com.luminex_studios.luminstatus.history.HistoryStore;
import com.luminex_studios.luminstatus.incident.IncidentManager;
import com.luminex_studios.luminstatus.model.Component;
import com.luminex_studios.luminstatus.model.ComponentState;
import com.luminex_studios.luminstatus.model.Incident;
import com.luminex_studios.luminstatus.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the live monitor state into the JSON document the page consumes.
 *
 * <p>Everything the public sees passes through here, which makes this the single
 * place to enforce the privacy rule: no addresses, no internal host names, no
 * stack traces. Only aliases, states, latencies and player counts leave the
 * proxy.
 */
public final class StatusSnapshot {

    private final HealthMonitor monitor;
    private final IncidentManager incidents;
    private final HistoryStore history;

    public StatusSnapshot(HealthMonitor monitor, IncidentManager incidents, HistoryStore history) {
        this.monitor = monitor;
        this.incidents = incidents;
        this.history = history;
    }

    /** Aggregate state across every visible component. */
    public ComponentState overallState() {
        List<Component> visible = visibleComponents();
        if (visible.isEmpty()) {
            return ComponentState.OPERATIONAL;
        }
        int down = 0;
        int maintenance = 0;
        boolean degraded = false;
        for (Component component : visible) {
            switch (component.state()) {
                case MAJOR_OUTAGE, PARTIAL_OUTAGE -> down++;
                case MAINTENANCE -> maintenance++;
                case DEGRADED -> degraded = true;
                default -> {
                }
            }
        }
        ComponentState fromComponents;
        if (down == visible.size()) {
            fromComponents = ComponentState.MAJOR_OUTAGE;
        } else if (down > 0) {
            fromComponents = ComponentState.PARTIAL_OUTAGE;
        } else if (maintenance == visible.size()) {
            fromComponents = ComponentState.MAINTENANCE;
        } else if (degraded) {
            fromComponents = ComponentState.DEGRADED;
        } else {
            fromComponents = ComponentState.OPERATIONAL;
        }

        ComponentState declared = ComponentState.OPERATIONAL;
        for (Incident incident : incidents.active()) {
            declared = ComponentState.worst(declared, incident.impact());
        }
        return ComponentState.worst(fromComponents, declared);
    }

    public List<Component> visibleComponents() {
        return monitor.components().values().stream()
                .filter(component -> !component.hidden())
                .sorted(Comparator.comparing(Component::group).thenComparing(Component::displayName))
                .toList();
    }

    /** Builds the full public document. */
    public Json.Obj build(StatusConfig config) {
        Json.Obj root = Json.obj();
        root.put("schema", 1);
        root.put("generated_at", Instant.now().getEpochSecond());
        root.put("title", config.page().title());
        root.put("tagline", config.page().tagline());
        root.put("support_url", config.page().supportUrl());
        root.put("footer", config.page().footer());
        root.put("retention_days", config.history().retentionDays());

        ComponentState overall = overallState();
        root.put("overall", Json.obj()
                .put("state", overall.id())
                .put("label", overall.label())
                .put("summary", summaryLine(overall)));

        int totalPlayers = 0;
        Map<String, List<Component>> grouped = new LinkedHashMap<>();
        for (Component component : visibleComponents()) {
            grouped.computeIfAbsent(component.group(), key -> new ArrayList<>()).add(component);
            if (component.onlinePlayers() > 0) {
                totalPlayers += component.onlinePlayers();
            }
        }
        root.put("players_online", totalPlayers);

        Json.Arr groups = Json.arr();
        for (Map.Entry<String, List<Component>> entry : grouped.entrySet()) {
            ComponentState groupState = ComponentState.OPERATIONAL;
            Json.Arr members = Json.arr();
            for (Component component : entry.getValue()) {
                groupState = ComponentState.worst(groupState, component.state());
                members.add(componentNode(component));
            }
            groups.add(Json.obj()
                    .put("name", entry.getKey())
                    .put("state", groupState.id())
                    .put("label", groupState.label())
                    .put("components", members));
        }
        root.put("groups", groups);

        Json.Arr incidentArray = Json.arr();
        List<Incident> shown = new ArrayList<>(incidents.active());
        shown.addAll(incidents.recentlyResolved(10));
        for (Incident incident : shown) {
            incidentArray.add(incidentNode(incident));
        }
        root.put("incidents", incidentArray);

        return root;
    }

    private Json.Obj componentNode(Component component) {
        Json.Arr timeline = Json.arr();
        for (DayRecord day : history.timeline(component.id())) {
            timeline.add(Json.obj()
                    .put("date", day.date().toString())
                    .put("uptime", day.total() == 0 ? -1.0D : round(day.uptime()))
                    .put("checks", day.total())
                    .put("latency", day.averageLatency()));
        }
        return Json.obj()
                .put("id", component.id())
                .put("name", component.displayName())
                .put("state", component.state().id())
                .put("label", component.state().label())
                .put("latency_ms", component.latencyMillis())
                .put("players", component.onlinePlayers())
                .put("changed_at", component.lastChange().getEpochSecond())
                .put("uptime", round(history.uptime(component.id())))
                .put("timeline", timeline);
    }

    private Json.Obj incidentNode(Incident incident) {
        Json.Arr updates = Json.arr();
        for (Incident.Update update : incident.updates()) {
            updates.add(Json.obj()
                    .put("at", update.at().getEpochSecond())
                    .put("stage", update.stage().id())
                    .put("stage_label", update.stage().label())
                    .put("message", update.message()));
        }
        Json.Arr components = Json.arr();
        for (String id : incident.components()) {
            monitor.component(id).ifPresent(component -> components.add(component.displayName()));
        }
        return Json.obj()
                .put("id", incident.id())
                .put("title", incident.title())
                .put("impact", incident.impact().id())
                .put("impact_label", incident.impact().label())
                .put("stage", incident.stage().id())
                .put("stage_label", incident.stage().label())
                .put("resolved", incident.resolved())
                .put("created_at", incident.createdAt().getEpochSecond())
                .put("resolved_at", incident.resolvedAt() == null ? 0 : incident.resolvedAt().getEpochSecond())
                .put("components", components)
                .put("updates", updates);
    }

    private String summaryLine(ComponentState overall) {
        long down = visibleComponents().stream().filter(c -> !c.state().isUp() && !c.maintenance()).count();
        return switch (overall) {
            case OPERATIONAL -> "All systems operational";
            case DEGRADED -> "Some servers are slower than usual";
            case MAINTENANCE -> "Planned maintenance in progress";
            case PARTIAL_OUTAGE -> down + (down == 1 ? " server is unreachable" : " servers are unreachable");
            case MAJOR_OUTAGE -> "The network is unreachable";
        };
    }

    private static double round(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }
}
