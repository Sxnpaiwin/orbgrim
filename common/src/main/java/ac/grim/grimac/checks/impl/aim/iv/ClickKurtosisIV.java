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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

/**
 * Grim-native port of Intave's {@code Kurtosis} click-pattern check
 * (source-available, intave/intave).
 *
 * <p>Detects non-human click-interval tail shape via excess kurtosis over a sliding
 * 25-interval window evaluated every swing: values below 6 (after Intave's /1000 scale)
 * accumulate quickly ({@code vl > 15}) while clean windows decay slowly. Fourth-moment
 * test — survives clickers tuned to beat mean/variance/entropy checks.</p>
 */
@CheckData(
        name = "ClickKurtosisIV",
        stableKey = "grim.combat.iv_click_kurtosis",
        description = "Non-human click-interval tail shape (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ClickKurtosisIV extends Check implements PacketReceiveListener {

    private static final long TIMEOUT_MS = 4000L;
    private static final int BUFFER = 25;

    private final Deque<Long> window = new ArrayDeque<>();
    private long lastSwing;
    private double vl;
    private long lastDigMs;
    private boolean enabled = true;

    public ClickKurtosisIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.click-kurtosis.enable", true);
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
            window.clear();
            lastSwing = now;
            return;
        }

        long diff = lastSwing == 0 ? -1 : now - lastSwing;
        lastSwing = now;
        if (diff < 0 || diff > TIMEOUT_MS) {
            window.clear();
            return;
        }

        window.offerFirst(diff);
        while (window.size() > BUFFER) window.removeLast();
        if (window.size() < BUFFER) return;

        double kurtosis = ClickStats.kurtosisQuirk(new ArrayList<>(window)) / 1000;
        if (kurtosis < 6) {
            if (++vl > 15) {
                flag("kurtosis h:" + (int) kurtosis);
                vl = 0;
            }
        } else {
            vl -= 0.1;
            vl *= 0.98;
            if (vl < 0) vl = 0;
        }
    }
}
