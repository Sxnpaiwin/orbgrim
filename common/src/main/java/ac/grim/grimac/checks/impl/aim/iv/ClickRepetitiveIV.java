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
 * Grim-native port of Intave's {@code Repetitive} click-pattern check
 * (source-available, intave/intave).
 *
 * <p>Loop detector for periodic CPS programs: per 10-swing chunk it records nonzero
 * ΔCPS into a pattern buffer, and at 100 entries (~1000 swings) tests for an exact
 * repeating period (lengths 2..n/2) or any adjacent pair within 0.001. Slowest window
 * of the family, aimed at macros that replay identical click programs.</p>
 */
@CheckData(
        name = "ClickRepetitiveIV",
        stableKey = "grim.combat.iv_click_repetitive",
        description = "Repeating click-program loop (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ClickRepetitiveIV extends Check implements PacketReceiveListener {

    private static final long TIMEOUT_MS = 4000L;
    private static final int BUFFER = 10;

    private final List<Long> chunk = new ArrayList<>();
    private final List<Double> pattern = new ArrayList<>();
    private long lastSwing;
    private double lastCps;
    private boolean hasLastCps;
    private double vl;
    private long lastDigMs;
    private boolean enabled = true;

    public ClickRepetitiveIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.click-repetitive.enable", true);
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
        if (diff < 0 || diff > TIMEOUT_MS) {
            chunk.clear();
            return;
        }
        chunk.add(diff);

        if (chunk.size() < BUFFER) return;
        double cps = ClickStats.chunkCps(chunk);
        chunk.clear();

        if (hasLastCps) {
            double delta = cps - lastCps;
            if (delta != 0) pattern.add(delta);
        }
        lastCps = cps;
        hasLastCps = true;

        if (pattern.size() >= 100) {
            double lastDelta = pattern.get(pattern.size() - 1);
            if (hasRepetitivePattern(0.001)) {
                if (++vl > 10) {
                    flag("repetitive clicks, std:" + String.format("%.3f", lastDelta));
                    vl = 0;
                }
            } else {
                vl -= 0.2;
                vl *= 0.98;
                if (vl < 0) vl = 0;
            }
            pattern.clear();
        }
    }

    private boolean hasRepetitivePattern(double epsilon) {
        int len = pattern.size();
        for (int period = 2; period <= len / 2; period++) {
            boolean tiling = true;
            for (int i = 0; i < len; i++) {
                if (!pattern.get(i).equals(pattern.get(i % period))) {
                    tiling = false;
                    break;
                }
            }
            if (tiling) return true;
        }
        for (int i = 1; i < len; i++) {
            if (Math.abs(pattern.get(i) - pattern.get(i - 1)) <= epsilon) return true;
        }
        return false;
    }
}
