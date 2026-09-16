package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Flags entity attacks that arrive with no arm-swing packet in the same tick batch.
 *
 * <p>A vanilla client sends an arm animation for every attack click, so a KillAura that
 * only fires attack packets (the most common silent-aura shortcut) is trivially
 * distinguishable. Structure mirrors Grim's own {@code NoSwingBreak}: swing/attack
 * presence is batched per tick and evaluated on the tick boundary, with a one-tick
 * grace for straddling batches under lag.</p>
 */
@CheckData(
        name = "NoSwing",
        stableKey = "grim.combat.no_swing",
        description = "Attacked an entity without swinging"
)
public class NoSwing extends Check implements PacketReceiveListener {

    private static final long JOIN_GRACE_MS = 5000L;

    private final long creationTime = System.currentTimeMillis();
    private boolean swungThisTick;
    private boolean swungLastTick;
    private boolean attackedThisTick;
    private boolean enabled = true;

    public NoSwing(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("NoSwing.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            swungThisTick = true;
        } else if (isAttack(event) && !event.isCancelled()) {
            attackedThisTick = true;
        }

        if (isTickPacket(event.getPacketType())) {
            if (attackedThisTick && !swungThisTick && !swungLastTick
                    && System.currentTimeMillis() - creationTime > JOIN_GRACE_MS) {
                flag("attack with no swing this or last tick");
            }
            swungLastTick = swungThisTick;
            swungThisTick = false;
            attackedThisTick = false;
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
