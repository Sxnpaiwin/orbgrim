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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code CivbreakHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Prevents the CivBreak instabreak exploit at the packet level: a
 * {@code STOP_DESTROY_BLOCK} with no preceding {@code START_DESTROY_BLOCK} is an
 * orphan the vanilla client never sends, so it is cancelled outright rather than
 * merely flagged. State machine only — START arms, any STOP disarms.</p>
 *
 * <p>Restricted to pre-1.14 clients like upstream: since 1.14 vanilla itself omits
 * START on repeat-breaks, enforcing the machine there would eat legitimate packets
 * (Intave carries the same TODO).</p>
 */
@CheckData(
        name = "CivbreakIV",
        stableKey = "grim.world.iv_civbreak",
        description = "Orphan block-stop instabreak guard (Intave port)",
        decay = 0.05,
        setback = 25
)
public class CivbreakIV extends Check implements PacketReceiveListener {

    private boolean mining;
    private boolean enabled = true;

    public CivbreakIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.civbreak.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) return;
        if (!player.getClientVersion().isOlderThan(ClientVersion.V_1_14)) return;

        DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
        if (action == DiggingAction.START_DIGGING) {
            mining = true;
        } else if (action == DiggingAction.CANCELLED_DIGGING) {
            if (!mining && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
            mining = false;
        }
    }
}
