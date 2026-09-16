package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of the packet-cadence half of Intave's {@code BlockingHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects 1.8 sword Autoblock via illegal block/unblock cadence: releasing the block
 * in the same tick it was placed, a second blocking interaction inside one client tick,
 * and zero client ticks between 255-face block toggles (buffered, flags above 2). The
 * movement-slowdown half is deliberately not ported — Grim's {@code NoSlow} prediction
 * already owns it.</p>
 *
 * <p>Scoped to pre-1.9 clients like upstream: sword blocking only exists there, so the
 * counters stay at zero on modern versions by construction.</p>
 */
@CheckData(
        name = "BlockingIV",
        stableKey = "grim.combat.iv_blocking",
        description = "Illegal sword block/unblock cadence (Intave port)",
        decay = 0.05,
        setback = 25
)
public class BlockingIV extends Check implements PacketReceiveListener {

    private int ticksBetweenBlockAndUnblock = 1000;
    private int toggleTimer;
    private boolean releasedAfterTick;
    private boolean toggleArmed;
    private int toggleVl;
    private boolean enabled = true;

    public BlockingIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.blocking.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;
        if (!player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) return;
        if (player.packetStateData.lastPacketWasTeleport) {
            ticksBetweenBlockAndUnblock = 1000;
            releasedAfterTick = false;
            toggleArmed = false;
            return;
        }

        if (isTickPacket(event.getPacketType())) {
            if (event.getPacketType() != PacketType.Play.Client.ANIMATION) {
                releasedAfterTick = false;
                ticksBetweenBlockAndUnblock++;
            }
            if (toggleArmed) toggleTimer++;
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            if (new WrapperPlayClientPlayerDigging(event).getAction() == DiggingAction.RELEASE_USE_ITEM) {
                releasedAfterTick = true;
                toggleArmed = true;
                if (ticksBetweenBlockAndUnblock == 0) {
                    flag("unblocked too quickly, 0 ticks");
                }
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement place = new WrapperPlayClientPlayerBlockPlacement(event);
            if (releasedAfterTick) {
                flag("sent multiple blocking interactions per tick");
            }

            boolean sword = isSwordInHand(place.getHand());
            if (place.getFaceId() == 255 && toggleArmed && sword) {
                int between = toggleTimer;
                toggleTimer = 0;
                toggleArmed = false;
                if (between == 0 && toggleVl < 20) {
                    toggleVl++;
                    if (toggleVl > 2) {
                        flag("sent too few packets between block-toggle packets, vl: " + toggleVl);
                    }
                } else if (toggleVl > 1) {
                    toggleVl -= 2;
                }
            }

            ticksBetweenBlockAndUnblock = 0;
        }
    }

    private boolean isSwordInHand(com.github.retrooper.packetevents.protocol.player.InteractionHand hand) {
        try {
            // ItemType#getName returns a Key ("minecraft:diamond_sword"); stringify, don't assume its type.
            String name = "" + player.inventory.getItemInHand(hand).getType().getName();
            return name.toUpperCase().endsWith("_SWORD");
        } catch (Exception e) {
            return false;
        }
    }
}
