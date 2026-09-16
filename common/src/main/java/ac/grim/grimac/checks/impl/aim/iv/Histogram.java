package ac.grim.grimac.checks.impl.aim.iv;

import java.util.Arrays;

/**
 * Verbatim port of Intave's {@code de.jpx3.intave.math.Histogram} (source-available,
 * intave/intave) — only the plotting helper dropped. Self-decaying bin counts
 * ({@code total > limit} halves everything) make it a rolling baseline, which is what
 * lets one player's normal jitter coexist with another player's fakelag gaps.
 */
public final class Histogram {

    private final double start;
    private final double end;
    private final double step;
    private final int[] bins;
    private final int limit;
    private double total;

    public Histogram(double start, double end, double step, int limit) {
        this.start = start;
        this.end = end;
        this.step = step;
        this.bins = new int[(int) Math.ceil((end - start) / step)];
        this.total = 0;
        this.limit = limit;
    }

    public void add(double value) {
        if (value < start || value > end) return;
        int index = (int) Math.floor((value - start) / step);
        if (index < 0 || index >= bins.length) return;
        bins[index]++;
        total++;
        if (total > limit) {
            total /= 2;
            for (int i = 0; i < bins.length; i++) bins[i] /= 2;
        }
    }

    public double mean() {
        double sum = 0;
        double count = 0;
        for (int i = 0; i < bins.length; i++) {
            sum += bins[i] * (start + i * step);
            count += bins[i];
        }
        return count == 0 ? 0 : sum / count;
    }

    public double variance() {
        double mean = mean();
        double sum = 0;
        double count = 0;
        for (int i = 0; i < bins.length; i++) {
            sum += bins[i] * Math.pow((start + i * step) - mean, 2);
            count += bins[i];
        }
        return count == 0 ? 0 : sum / count;
    }

    public double standardDeviation() {
        return Math.sqrt(variance());
    }

    public double normalProbability(double value) {
        double variance = variance();
        if (variance <= 0) return value == mean() ? 1 : 0;
        return Math.exp(-Math.pow(value - mean(), 2) / (2 * variance))
                / Math.sqrt(2 * Math.PI * variance);
    }

    public int size() {
        return (int) total;
    }

    public void clear() {
        Arrays.fill(bins, 0);
        total = 0;
    }
}
