package com.luminex_studios.luminstatus.history;

import com.luminex_studios.luminstatus.util.Json;
import com.luminex_studios.luminstatus.model.ComponentState;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling daily uptime history, persisted as a single JSON file.
 *
 * <p>Writes are atomic (temp file plus move), so a crash mid-save leaves the
 * previous file intact rather than a truncated one.
 */
public final class HistoryStore {

    private final Path file;
    private final Logger logger;
    private final int retentionDays;
    private final Map<String, Map<LocalDate, DayRecord>> byComponent = new ConcurrentHashMap<>();

    public HistoryStore(Path dataDirectory, Logger logger, int retentionDays) {
        this.file = dataDirectory.resolve("history.json");
        this.logger = logger;
        this.retentionDays = retentionDays;
    }

    /** Folds one check result into today's bucket. */
    public void record(String componentId, ComponentState state, long latencyMillis) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        DayRecord day = byComponent
                .computeIfAbsent(componentId, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(today, DayRecord::new);
        switch (state) {
            case OPERATIONAL -> day.addUp(latencyMillis);
            case DEGRADED -> day.addDegraded(latencyMillis);
            case MAINTENANCE -> day.addMaintenance();
            default -> day.addDown();
        }
    }

    /**
     * The last {@code retentionDays} days for a component, oldest first. Days
     * with no data are present with zero totals so the page can render a gap
     * rather than shifting the bars.
     */
    public List<DayRecord> timeline(String componentId) {
        Map<LocalDate, DayRecord> days = byComponent.getOrDefault(componentId, Map.of());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<DayRecord> out = new ArrayList<>(retentionDays);
        for (int i = retentionDays - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            DayRecord record = days.get(date);
            out.add(record != null ? record : new DayRecord(date));
        }
        return out;
    }

    /** Uptime across the retention window as a fraction of 1. */
    public double uptime(String componentId) {
        long up = 0;
        long total = 0;
        for (DayRecord day : timeline(componentId)) {
            long denominator = day.up() + day.degraded() + day.down();
            up += day.up() + day.degraded();
            total += denominator;
        }
        return total == 0 ? 1.0D : (double) up / total;
    }

    public void prune() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        for (Map<LocalDate, DayRecord> days : byComponent.values()) {
            days.keySet().removeIf(date -> date.isBefore(cutoff));
        }
    }

    public void load() {
        if (Files.notExists(file)) {
            return;
        }
        try {
            Object parsed = Json.read(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Json.Obj root)) {
                logger.warn("history.json is not a JSON object, starting with empty history");
                return;
            }
            for (String componentId : root.keys()) {
                Json.Obj daysNode = root.obj(componentId);
                if (daysNode == null) {
                    continue;
                }
                Map<LocalDate, DayRecord> days = new ConcurrentHashMap<>();
                for (String dateKey : daysNode.keys()) {
                    Json.Obj entry = daysNode.obj(dateKey);
                    if (entry == null) {
                        continue;
                    }
                    LocalDate date = LocalDate.parse(dateKey);
                    DayRecord record = new DayRecord(date);
                    record.load(
                            entry.number("up", 0),
                            entry.number("degraded", 0),
                            entry.number("down", 0),
                            entry.number("maintenance", 0),
                            entry.number("latency_sum", 0),
                            entry.number("latency_samples", 0)
                    );
                    days.put(date, record);
                }
                byComponent.put(componentId, days);
            }
            prune();
        } catch (IOException | RuntimeException ex) {
            logger.warn("Could not read history.json, starting with empty history", ex);
        }
    }

    public void save() {
        Json.Obj root = Json.obj();
        for (Map.Entry<String, Map<LocalDate, DayRecord>> entry : byComponent.entrySet()) {
            Json.Obj days = Json.obj();
            Map<LocalDate, DayRecord> sorted = new LinkedHashMap<>();
            entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            for (Map.Entry<LocalDate, DayRecord> dayEntry : sorted.entrySet()) {
                DayRecord day = dayEntry.getValue();
                days.put(dayEntry.getKey().toString(), Json.obj()
                        .put("up", day.up())
                        .put("degraded", day.degraded())
                        .put("down", day.down())
                        .put("maintenance", day.maintenance())
                        .put("latency_sum", day.latencySum())
                        .put("latency_samples", day.latencySamples()));
            }
            root.put(entry.getKey(), days);
        }
        writeAtomically(file, Json.write(root), logger);
    }

    /** Writes to a sibling temp file and moves it into place. */
    public static void writeAtomically(Path target, String content, Logger logger) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            logger.warn("Could not write {}", target, ex);
        }
    }
}
