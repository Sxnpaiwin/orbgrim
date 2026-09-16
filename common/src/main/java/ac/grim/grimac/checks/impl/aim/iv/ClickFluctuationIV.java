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
 * Grim-native port of Intave's {@code Fluctuation} click-pattern check
 * (source-available, intave/intave).
 *
 * <p>Detects clickers with weak extra randomization (Vape/Karma-style jitter): per
 * 10-swing chunk it derives CPS and records spikes/drops beyond ±0.45; when three
 * spikes or three drops land with metronome-regular timestamps ({@code stddev < 1200ms})
 * the "randomization" is rhythmic and it flags. Slow legit fighting (≤1/s gaps) is
 * exempt by design.</p>
 */
@CheckData(
        name = "ClickFluctuationIV",
        stableKey = "grim.combat.iv_click_fluctuation",
        description = "Rhythmic CPS fluctuation in jitter clickers (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ClickFluctuationIV extends Check implements PacketReceiveListener {

    private static final long TIMEOUT_MS = 4000L;
    private static final int BUFFER = 10;

    private final List<Long> chunk = new ArrayList<>();
    private final List<Long> spikes = new ArrayList<>();
    private final List<Long> drops = new ArrayList<>();
    private long lastSwing;
    private double lastCps;
    private boolean hasLastCps;
    private double vl;
    private long lastDigMs;
    private boolean enabled = true;

    public ClickFluctuationIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.click-fluctuation.enable", true);
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
            chunk.clear();
            lastSwing = now;
            return;
        }

        long diff = lastSwing == 0 ? -1 : now - lastSwing;
        lastSwing = now;
        if (diff < 0) return;
        if (diff > 10_000) {
            spikes.clear();
            drops.clear();
            chunk.clear();
            return;
        }
        if (diff > 3000) return;
        if (diff > TIMEOUT_MS) {
            chunk.clear();
            return;
        }
        chunk.add(diff);

        if (chunk.size() < BUFFER) return;
        double cps = ClickStats.chunkCps(chunk);
        chunk.clear();

        if (hasLastCps) {
            double delta = cps - lastCps;
            if (delta > 0.45) spikes.add(now);
            else if (delta < -0.45) drops.add(now);
        }
        lastCps = cps;
        hasLastCps = true;

        if (spikes.size() >= 3) {
            if (ClickStats.stddev(spikes) < 1200) {
                if (++vl > 3) {
                    flag("balanced randomization");
                    vl = 0;
                }
            } else {
                decay();
            }
            spikes.clear();
        }
        if (drops.size() >= 3) {
            if (ClickStats.stddev(drops) < 1200) {
                if (++vl > 3) {
                    flag("balanced randomization");
                    vl = 0;
                }
            } else {
                decay();
            }
            drops.clear();
        }
    }

    private void decay() {
        vl -= 0.2;
        vl *= 0.98;
        if (vl < 0) vl = 0;
    }
}
