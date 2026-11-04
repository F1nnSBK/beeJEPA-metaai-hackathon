package com.beejepa.event;

/**
 * Statistical and numeric helpers for event catalog metrics.
 */
public final class CatalogStatistics {

    private CatalogStatistics() {}

    /** Computes the linear-interpolated quantile {@code q} in {@code [0, 1]} over a sorted array. */
    public static double percentile(double[] sorted, double q) {
        if (sorted == null || sorted.length == 0) {
            return Double.NaN;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    public static double round(double v, int digits) {
        if (Double.isNaN(v)) {
            return v;
        }
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}

