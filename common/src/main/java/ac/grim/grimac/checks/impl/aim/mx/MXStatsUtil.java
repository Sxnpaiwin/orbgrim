package ac.grim.grimac.checks.impl.aim.mx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Faithful, dependency-free port of the subset of MX-Project's
 * {@code kireiko.dev.millennium.math.Statistics} used by its aim checks.
 *
 * <p>Source: MX-Project {@code Statistics.java} (Unlicense, by Kireiko).
 * Guava usage ({@code Lists.newArrayList}) replaced with plain JDK so Grim
 * (Gradle, no Guava on common) compiles cleanly. Formulas are unchanged.</p>
 */
public final class MXStatsUtil {

    private MXStatsUtil() {}

    public static double getAverage(final Collection<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0;
        double sum = 0.0;
        for (final Number number : data) sum += number.doubleValue();
        final double result = sum / data.size();
        return Double.isNaN(result) ? 0 : result;
    }

    public static double getVariance(final Collection<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0;
        int count = 0;
        double sum = 0.0;
        for (final Number number : data) {
            sum += number.doubleValue();
            ++count;
        }
        if (count == 0) return 0;
        double average = sum / count;
        double variance = 0.0;
        for (final Number number : data) {
            variance += Math.pow(number.doubleValue() - average, 2.0);
        }
        return variance / count;
    }

    public static double getStandardDeviation(final Collection<? extends Number> data) {
        return Math.sqrt(getVariance(data));
    }

    public static double getMin(final Collection<? extends Number> collection) {
        double min = Double.MAX_VALUE;
        for (final Number number : collection) min = Math.min(min, number.doubleValue());
        return min;
    }

    public static double getMax(final Collection<? extends Number> collection) {
        double max = -Double.MAX_VALUE;
        for (final Number number : collection) max = Math.max(max, number.doubleValue());
        return max;
    }

    /** MX: {@code getGCD(s) * 0.15F} — vanilla sensitivity GCD helper. */
    public static float getGCDValue(double s) {
        float f1 = (float) ((float) s * 0.6 + 0.2);
        float gcd = f1 * f1 * f1 * 8.0F;
        return gcd * 0.15F;
    }

    /** MX: Shannon entropy base-2 over exact double frequencies. */
    public static double getShannonEntropy(final Collection<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0;
        Map<Double, Long> freq = new HashMap<>();
        for (Number n : data) {
            double d = n.doubleValue();
            freq.put(d, freq.getOrDefault(d, 0L) + 1L);
        }
        double total = data.size();
        double entropy = 0;
        for (long count : freq.values()) {
            double p = count / total;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /** MX: repeated abs-of-abs differencing, {@code depth} times. */
    public static List<Float> getJiffDelta(List<? extends Number> data, int depth) {
        List<Float> result = new ArrayList<>(data.size());
        for (Number n : data) result.add(n.floatValue());
        for (int i = 0; i < depth; i++) {
            List<Float> next = new ArrayList<>(Math.max(0, result.size() - 1));
            boolean first = true;
            float old = 0;
            for (float n : result) {
                if (first) {
                    old = n;
                    first = false;
                    continue;
                }
                next.add(Math.abs(Math.abs(n) - Math.abs(old)));
                old = n;
            }
            result = next;
            if (result.isEmpty()) break;
        }
        return result;
    }

    /** MX: values with |z| > threshold (note: MX does not guard stdDev==0; we do). */
    public static List<Double> getZScoreOutliers(final Collection<? extends Number> data, double threshold) {
        List<Double> outliers = new ArrayList<>();
        double mean = getAverage(data);
        double stdDev = getStandardDeviation(data);
        if (stdDev == 0) return outliers;
        for (Number number : data) {
            double z = (number.doubleValue() - mean) / stdDev;
            if (Math.abs(z) > threshold) outliers.add(number.doubleValue());
        }
        return outliers;
    }

    public static int getDistinct(final Collection<? extends Number> data) {
        return (int) data.stream().distinct().count();
    }

    public static double getIQR(final Collection<? extends Number> data) {
        List<Double> sorted = data.stream().map(Number::doubleValue).sorted().collect(Collectors.toList());
        if (sorted.isEmpty()) return 0;
        return percentile(sorted, 75) - percentile(sorted, 25);
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /** MX AimComplexCheck helper: | |a| - |b| |. */
    public static double getDifference(double a, double b) {
        return Math.abs(Math.abs(a) - Math.abs(b));
    }

    /** MX AimComplexCheck helper: vanilla GCD long path. */
    public static long getGcd(final long current, final long previous) {
        return (previous <= 16384L) ? current : getGcd(previous, current % previous);
    }

    public static long getAbsoluteGcd(final float current, final float last) {
        final double EXPANDER = Math.pow(2, 24);
        long currentExpanded = (long) (current * EXPANDER);
        long lastExpanded = (long) (last * EXPANDER);
        return getGcd(currentExpanded, lastExpanded);
    }
}
