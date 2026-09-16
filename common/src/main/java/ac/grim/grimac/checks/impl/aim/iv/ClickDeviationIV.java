package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Grim-native port of Intave's {@code Deviation} click-pattern check
 * (source-available, intave/intave).
 *
 * <p>Detects inhumanly stable click tempo via two-level variance-of-variance: every 50
 * swings the interval stddev is pushed to a second buffer, and once 3 windows (150
 * swings) exist, the stddev <i>of those stddevs</i> below 25 flags — a flat autoclicker
 * is consistent about being consistent. Requires the 150-swing window under 4s and a
 * repeat ({@code vl > 2}) before flagging.</p>
 */
@CheckData(
        name = "ClickDeviationIV",
        stableKey = "grim.combat.iv_click_deviation",
        description = "Inhumanly stable click tempo (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ClickDeviationIV extends Check implements PacketReceiveListener {

    private static final long TIMEOUT_MS = 4000L;
    private static final int BUFFER = 50;

    private final List<Long> intervals = new ArrayList<>();
    private final List<Double> deviations = new ArrayList<>();
    private long lastSwing;
    private long windowStart;
    private double vl;
    private long lastDigMs;
    private boolean enabled = true;

    public ClickDeviationIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.click-deviation.enable", true);
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
            deviations.clear();
            lastSwing = now;
            return;
        }

        long diff = lastSwing == 0 ? -1 : now - lastSwing;
        lastSwing = now;
        if (diff < 0 || diff > TIMEOUT_MS) {
            intervals.clear();
            deviations.clear();
            windowStart = now;
            return;
        }
        if (intervals.isEmpty()) windowStart = now;
        intervals.add(diff);

        if (intervals.size() >= BUFFER) {
            deviations.add(ClickStats.stddev(intervals));
            intervals.clear();
        }
        if (deviations.size() >= 3) {
            double std = ClickStats.stddev(deviations);
            if (std < 25 && now - windowStart < TIMEOUT_MS) {
                vl += std < 10 ? 2 : 1;
                if (vl > 2) {
                    flag("low deviation, sd:" + String.format("%.3f", std));
                    vl = 0;
                }
            } else {
                vl -= 0.1;
                vl *= 0.9;
                if (vl < 0) vl = 0;
            }
            deviations.clear();
            windowStart = now;
        }
    }
}
