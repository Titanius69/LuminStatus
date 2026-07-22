package com.luminex_studios.luminstatus.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** An operator-authored incident shown at the top of the status page. */
public final class Incident {

    /** Lifecycle of an incident, using vocabulary operators already recognise. */
    public enum Stage {
        INVESTIGATING,
        IDENTIFIED,
        MONITORING,
        RESOLVED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String label() {
            return switch (this) {
                case INVESTIGATING -> "Investigating";
                case IDENTIFIED -> "Identified";
                case MONITORING -> "Monitoring";
                case RESOLVED -> "Resolved";
            };
        }

        public static Stage parse(String raw) {
            for (Stage stage : values()) {
                if (stage.name().equalsIgnoreCase(raw)) {
                    return stage;
                }
            }
            return INVESTIGATING;
        }
    }

    /** A timestamped note appended to an incident. */
    public record Update(Instant at, Stage stage, String message) {
    }

    private final String id;
    private final String title;
    private final ComponentState impact;
    private final Set<String> components = new LinkedHashSet<>();
    private final List<Update> updates = new ArrayList<>();
    private final Instant createdAt;
    private Stage stage;
    private Instant resolvedAt;

    public Incident(String id, String title, ComponentState impact, Set<String> components, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.impact = impact;
        this.components.addAll(components);
        this.createdAt = createdAt;
        this.stage = Stage.INVESTIGATING;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public ComponentState impact() {
        return impact;
    }

    public Set<String> components() {
        return components;
    }

    public List<Update> updates() {
        return List.copyOf(updates);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Stage stage() {
        return stage;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public boolean resolved() {
        return stage == Stage.RESOLVED;
    }

    public void addUpdate(Stage stage, String message) {
        this.stage = stage;
        this.updates.add(new Update(Instant.now(), stage, message));
        if (stage == Stage.RESOLVED) {
            this.resolvedAt = Instant.now();
        }
    }

    /** Restores persisted state without appending a new update entry. */
    public void restore(Stage stage, Instant resolvedAt, List<Update> loaded) {
        this.stage = stage;
        this.resolvedAt = resolvedAt;
        this.updates.clear();
        this.updates.addAll(loaded);
    }
}
