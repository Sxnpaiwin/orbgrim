package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Grim-native port of Intave's {@code Entropy} click-pattern check
 * (source-available, intave/intave).
 *
 * <p>Detects low-randomness timer clickers two ways: fast path flags a single 100-swing
 * window whose interval Shannon entropy sits in the uncanny {@code [0.35, 1]} band;
 * slow path flags four consecutive windows (400 swings) with near-identical entropy
 * ({@code stddev < 0.3}) — balanced distributions no human reproduces. No relation to
 * {@code AimEntropyMX}, which measures <i>rotation</i> entropy; this is click timing.</p>
 */
@CheckData(
        name = "ClickEntropyIV",
        stableKey = "grim.combat.iv_click_entropy",
        description = "Low-randomness click timing distribution (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ClickEntropyIV extends Check implements PacketReceiveListener {

    private static final long TIMEOUT_MS = 4000L;
    private static final int BUFFER = 100;

    private final List<Long> intervals = new ArrayList<>();
    private final List<Double> entropySamples = new ArrayList<>();
    private long lastSwing;
    private long windowStart;
    private double vl;
    private long lastDigMs;
    private boolean enabled = true;

    public ClickEntropyIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.click-entropy.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            lastDigMs = System.currentTimeMillis();
            return;
        }
        if (event.getPacketType() != PacketType.Play.Client.ANIMATION) return;
        if (player.packetStateData.lastPacketWasTeleport) return;

        long now = System.currentTimeMillis();
        if (System.currentTimeMillis() - lastDigMs < 3000 || ClickStats.holdingRod(player)) {
            intervals.clear();
            entropySamples.clear();
            lastSwing = now;
            return;
        }

        long diff = lastSwing == 0 ? -1 : now - lastSwing;
        lastSwing = now;
        if (diff < 0 || diff > TIMEOUT_MS) {
            intervals.clear();
            windowStart = now;
            return;
        }
        if (intervals.isEmpty()) windowStart = now;
        intervals.add(diff);

        if (intervals.size() >= BUFFER) {
            double entropy = ClickStats.shannonEntropy(intervals);
            intervals.clear();
            if (entropy >= 0.35 && entropy <= 1 && now - windowStart < TIMEOUT_MS) {
                vl += 2;
                if (vl > 1) {
                    flag("low entropy, e:" + String.format("%.3f", entropy));
                    vl = 0;
                }
            } else {
                vl -= 0.2;
                vl *= 0.98;
                if (vl < 0) vl = 0;
            }

            entropySamples.add((double) (long) entropy);
            if (entropySamples.size() >= 4) {
                if (ClickStats.stddev(entropySamples) < 0.3 && now - windowStart < TIMEOUT_MS) {
                    vl += 2;
                    if (vl > 3) {
                        flag("balanced entropy, sd:" + String.format("%.3f", ClickStats.stddev(entropySamples)));
                        vl = 0;
                    }
                } else {
                    vl -= 0.2;
                    vl *= 0.98;
                    if (vl < 0) vl = 0;
                }
                entropySamples.clear();
            }
            windowStart = now;
        }
    }
}
