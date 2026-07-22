package com.luminex_studios.luminstatus.model;

import java.util.Locale;

/**
 * The health of a single component, ordered from best to worst.
 *
 * <p>The ordering matters: aggregating a group of components is done by taking
 * the worst state present, which is simply the highest {@link Enum#ordinal()}.
 */
public enum ComponentState {

    /** Reachable and responding within the expected latency band. */
    OPERATIONAL("Operational", 0x2E9E6A),

    /** Reachable, but noticeably slower than its own baseline. */
    DEGRADED("Degraded performance", 0xC98A16),

    /** Announced downtime. Never triggers alerts. */
    MAINTENANCE("Under maintenance", 0x5B6B7F),

    /** Part of the group is unreachable. Only used for aggregates. */
    PARTIAL_OUTAGE("Partial outage", 0xD4633B),

    /** Unreachable. */
    MAJOR_OUTAGE("Major outage", 0xB3243B);

    private final String label;
    private final int color;

    ComponentState(String label, int color) {
        this.label = label;
        this.color = color;
    }

    /** Human readable label shown on the public page. */
    public String label() {
        return label;
    }

    /** RGB colour used by the page and by Discord embeds. */
    public int color() {
        return color;
    }

    /** Machine readable identifier used in the JSON API. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isUp() {
        return this == OPERATIONAL || this == DEGRADED;
    }

    /** Returns the worse of the two states. */
    public static ComponentState worst(ComponentState a, ComponentState b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
