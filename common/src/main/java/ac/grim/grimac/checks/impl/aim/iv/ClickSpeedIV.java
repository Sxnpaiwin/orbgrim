package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code ClickSpeedLimiter}
 * (source-available, intave/intave).
 *
 * <p>Hard per-second attack budget independent of click regularity: attacks are binned
 * into a rolling 20-tick ring and any 20-tick sum above {@code max-cps} flags, plus all
 * attacks are muted for one second after a flag. This is the ceiling the click-pattern
 * checks sit under — raw CPS flooding with no regard for rhythm.</p>
 *
 * <p>Deviations: Intave's manual tick-end/position-reminder redistribution (for 1.9–1.20
 * clients that skip movement ticks) is replaced by Grim's {@code isTickPacket}, which
 * already normalizes tick boundaries including the 1.21.2+ tick-end fallback — same
 * coverage, none of the version branching. The "certain" nuance
 * ({@code countAccuratePositionPackets > 20}) collapses accordingly: every Grim tick
 * boundary is accurate by construction.</p>
 */
@CheckData(
        name = "ClickSpeedIV",
        stableKey = "grim.combat.iv_click_speed",
        description = "Attack rate above per-second budget (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ClickSpeedIV extends Check implements PacketReceiveListener {

    private final int[] ring = new int[20];
    private int index;
    private int tickAttacks;
    private long lastFlagMs;
    private int maxCps = 20;
    private boolean enabled = true;

    public ClickSpeedIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        maxCps = Math.max(8, Math.min(40, config.getIntElse("Intave.click-speed.max-cps", 20)));
        enabled = config.getBooleanElse("Intave.click-speed.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || player.gamemode == GameMode.SPECTATOR) return;

        if (isAttack(event)) {
            if (System.currentTimeMillis() - lastFlagMs < 1000 && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                return;
            }
            tickAttacks++;
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;

        ring[index] = tickAttacks;
        tickAttacks = 0;
        index = (index + 1) % ring.length;

        int sum = 0;
        for (int c : ring) sum += c;
        if (sum > maxCps) {
            flag("attacked too quickly, " + sum + " c/s");
            lastFlagMs = System.currentTimeMillis();
        }
    }

    private static boolean isAttack(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) return true;
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            return new WrapperPlayClientInteractEntity(event).getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        }
        return false;
    }
}
