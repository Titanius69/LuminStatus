package com.luminex_studios.luminstatus.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A single monitored backend server, as shown on the status page.
 *
 * <p>Mutable and guarded by its own monitor. Mutation happens on the health
 * monitor thread; reads happen on HTTP threads, so every accessor is
 * synchronized. The object is small and contended rarely enough that the lock
 * cost is irrelevant.
 */
public final class Component {

    private final String id;
    private final String displayName;
    private final String group;
    private final boolean hidden;

    private ComponentState state = ComponentState.OPERATIONAL;
    private ComponentState rawState = ComponentState.OPERATIONAL;
    private boolean maintenance;
    private long latencyMillis = -1;
    private int onlinePlayers = -1;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private Instant lastChange = Instant.now();
    private Instant lastCheck = Instant.EPOCH;
    private String lastError;

    public Component(String id, String displayName, String group, boolean hidden) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.group = group == null || group.isBlank() ? "Network" : group;
        this.hidden = hidden;
    }

    public String id() {
        return id;
    }

    /** The alias shown publicly. Never the internal server name unless configured so. */
    public String displayName() {
        return displayName;
    }

    public String group() {
        return group;
    }

    public boolean hidden() {
        return hidden;
    }

    public synchronized ComponentState state() {
        return state;
    }

    public synchronized ComponentState rawState() {
        return rawState;
    }

    public synchronized long latencyMillis() {
        return latencyMillis;
    }

    public synchronized int onlinePlayers() {
        return onlinePlayers;
    }

    public synchronized Instant lastChange() {
        return lastChange;
    }

    public synchronized Instant lastCheck() {
        return lastCheck;
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized boolean maintenance() {
        return maintenance;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized int consecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public synchronized void setMaintenance(boolean maintenance) {
        this.maintenance = maintenance;
        applyState(rawState);
    }

    public synchronized void recordSuccess(long latencyMillis, int onlinePlayers, boolean degraded) {
        this.consecutiveFailures = 0;
        this.consecutiveSuccesses++;
        this.latencyMillis = latencyMillis;
        this.onlinePlayers = onlinePlayers;
        this.lastCheck = Instant.now();
        this.lastError = null;
        applyState(degraded ? ComponentState.DEGRADED : ComponentState.OPERATIONAL);
    }

    /**
     * Records a failed check. The component only flips to an outage after
     * {@code failureThreshold} consecutive failures, which is what keeps a single
     * dropped packet from creating an incident.
     */
    public synchronized void recordFailure(String error, int failureThreshold) {
        this.consecutiveSuccesses = 0;
        this.consecutiveFailures++;
        this.lastCheck = Instant.now();
        this.lastError = error;
        if (consecutiveFailures >= failureThreshold) {
            this.latencyMillis = -1;
            this.onlinePlayers = -1;
            applyState(ComponentState.MAJOR_OUTAGE);
        }
    }

    private void applyState(ComponentState candidate) {
        this.rawState = candidate;
        ComponentState effective = maintenance ? ComponentState.MAINTENANCE : candidate;
        if (effective != state) {
            state = effective;
            lastChange = Instant.now();
        }
    }
}
