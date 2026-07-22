package com.luminex_studios.luminstatus.history;

import java.time.LocalDate;

/**
 * One day of aggregated check results for one component.
 *
 * <p>Only counters are kept, never individual samples. Ninety days of history
 * for a fifty-server network is a few kilobytes, which is why this needs no
 * database.
 */
public final class DayRecord {

    private final LocalDate date;
    private long up;
    private long degraded;
    private long down;
    private long maintenance;
    private long latencySum;
    private long latencySamples;

    public DayRecord(LocalDate date) {
        this.date = date;
    }

    public LocalDate date() {
        return date;
    }

    public long up() {
        return up;
    }

    public long degraded() {
        return degraded;
    }

    public long down() {
        return down;
    }

    public long maintenance() {
        return maintenance;
    }

    public long total() {
        return up + degraded + down + maintenance;
    }

    /** Average latency in milliseconds, or -1 when the day has no samples. */
    public long averageLatency() {
        return latencySamples == 0 ? -1 : latencySum / latencySamples;
    }

    /**
     * Uptime for the day as a fraction of 1. Maintenance windows are excluded
     * from the denominator: planned downtime is not an outage.
     */
    public double uptime() {
        long denominator = up + degraded + down;
        return denominator == 0 ? 1.0D : (double) (up + degraded) / denominator;
    }

    public void addUp(long latencyMillis) {
        up++;
        addLatency(latencyMillis);
    }

    public void addDegraded(long latencyMillis) {
        degraded++;
        addLatency(latencyMillis);
    }

    public void addDown() {
        down++;
    }

    public void addMaintenance() {
        maintenance++;
    }

    private void addLatency(long latencyMillis) {
        if (latencyMillis >= 0) {
            latencySum += latencyMillis;
            latencySamples++;
        }
    }

    public void load(long up, long degraded, long down, long maintenance, long latencySum, long latencySamples) {
        this.up = up;
        this.degraded = degraded;
        this.down = down;
        this.maintenance = maintenance;
        this.latencySum = latencySum;
        this.latencySamples = latencySamples;
    }

    public long latencySum() {
        return latencySum;
    }

    public long latencySamples() {
        return latencySamples;
    }
}
