package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared math for the Intave-ported click-timing checks ({@code ClickPatterns} family).
 *
 * <p>Formulas mirror {@code de.jpx3.intave.check.combat.clickpatterns} exactly, including
 * its quirks (see {@link #kurtosisQuirk}). Where the standard population statistic
 * applies, callers should prefer {@code MXStatsUtil}.</p>
 */
public final class ClickStats {

    private ClickStats() {}

    /** Shannon entropy base-2 over exact-value frequencies (Intave {@code Entropy}). */
    public static double shannonEntropy(List<Long> intervals) {
        if (intervals == null || intervals.isEmpty()) return 0;
        Map<Long, Long> freq = new HashMap<>();
        for (Long v : intervals) freq.put(v, freq.getOrDefault(v, 0L) + 1L);
        double total = intervals.size();
        double entropy = 0;
        for (long count : freq.values()) {
            double p = count / total;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /**
     * Intave's {@code kurtosisOf} verbatim — including its quirk of dividing by the value
     * {@code sum} where the textbook formula uses {@code n}. Callers divide by 1000 and
     * compare against 6, exactly like upstream.
     */
    public static double kurtosisQuirk(List<Long> data) {
        int n = data.size();
        double sum = 0;
        for (long v : data) sum += v;
        double mean = sum / n;
        double s2 = 0;
        double s4 = 0;
        for (long v : data) {
            double d = v - mean;
            s2 += d * d;
            s4 += d * d * d * d;
        }
        double d2 = (double) n * (n + 1) / ((n - 1) * (n - 2) * (n - 3));
        double d3 = 3.0 * (n - 1) * (n - 1) / ((n - 2) * (n - 3));
        double denom = s2 / sum;
        if (denom == 0) return 0;
        return d2 * (s4 / (denom * denom)) - d3;
    }

    public static double stddev(Collection<? extends Number> data) {
        if (data == null || data.isEmpty()) return 0;
        double sum = 0;
        for (Number v : data) sum += v.doubleValue();
        double mean = sum / data.size();
        double var = 0;
        for (Number v : data) var += (v.doubleValue() - mean) * (v.doubleValue() - mean);
        return Math.sqrt(var / data.size());
    }

    /** Intave's chunk-CPS: {@code 20/sum*50} over a 10-swing interval chunk (scale kept). */
    public static double chunkCps(List<Long> chunk) {
        long sum = 0;
        for (long v : chunk) sum += v;
        if (sum <= 0) return 0;
        return 20.0 / sum * 50;
    }

    public static boolean holdingRod(GrimPlayer player) {
        try {
            String name = "" + player.inventory.getItemInHand(InteractionHand.MAIN_HAND).getType().getName();
            return name.toUpperCase().endsWith("_ROD");
        } catch (Exception e) {
            return false;
        }
    }
}
