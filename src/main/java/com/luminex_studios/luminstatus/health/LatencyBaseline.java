package com.luminex_studios.luminstatus.health;

import java.util.Arrays;

/**
 * Rolling latency baseline for one component.
 *
 * <p>"Degraded" is defined relative to a server's own history rather than an
 * absolute threshold, because a 90 ms ping is normal for a server three
 * datacentres away and alarming for one in the same rack. The baseline is the
 * median of the last N samples: the median ignores the occasional spike that
 * would drag a mean upwards and hide real regressions.
 */
public final class LatencyBaseline {

    /** Below this, relative comparisons are noise, so nothing is ever degraded. */
    private static final long NOISE_FLOOR_MILLIS = 25L;

    private final long[] samples;
    private int count;
    private int cursor;

    public LatencyBaseline(int capacity) {
        this.samples = new long[Math.max(5, capacity)];
    }

    public synchronized void add(long latencyMillis) {
        if (latencyMillis < 0) {
            return;
        }
        samples[cursor] = latencyMillis;
        cursor = (cursor + 1) % samples.length;
        if (count < samples.length) {
            count++;
        }
    }

    /** Median of the collected samples, or -1 when there is not enough data yet. */
    public synchronized long median() {
        if (count < 5) {
            return -1;
        }
        long[] copy = Arrays.copyOf(samples, count);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    /**
     * Whether the supplied sample counts as degraded.
     *
     * @param latencyMillis the fresh sample
     * @param multiplier    how many times the baseline counts as degraded
     */
    public synchronized boolean isDegraded(long latencyMillis, double multiplier) {
        long median = median();
        if (median < 0 || latencyMillis < NOISE_FLOOR_MILLIS) {
            return false;
        }
        return latencyMillis > Math.max(NOISE_FLOOR_MILLIS, median * multiplier);
    }
}
