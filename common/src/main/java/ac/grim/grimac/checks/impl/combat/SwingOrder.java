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
 * Enforces vanilla swing/attack packet ordering: every attack must be preceded by its
 * own swing packet, with no second attack in between.
 *
 * <p>Complements {@link NoSwing}: an aura that spoofs one swing per burst (or swings
 * <i>after</i> attacking) passes the per-tick presence test but fails here on the
 * second attack that shares the stale swing. TCP preserves packet order per connection,
 * so a second attack with no intervening swing cannot happen legitimately — each click
 * sends its own animation.</p>
 */
@CheckData(
        name = "SwingOrder",
        stableKey = "grim.combat.swing_order",
        description = "Attacked twice with no swing in between"
)
public class SwingOrder extends Check implements PacketReceiveListener {

    private static final long JOIN_GRACE_MS = 5000L;

    private final long creationTime = System.currentTimeMillis();
    private int attacksSinceSwing;
    private boolean enabled = true;

    public SwingOrder(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("SwingOrder.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            attacksSinceSwing = 0;
            return;
        }

        if (isAttack(event) && !event.isCancelled()) {
            attacksSinceSwing++;
            if (attacksSinceSwing > 1
                    && System.currentTimeMillis() - creationTime > JOIN_GRACE_MS) {
                flag(attacksSinceSwing + " attacks with no swing in between");
                // Stay at 1 so a swingless aura keeps flagging per attack instead of
                // the counter running away on a single burst.
                attacksSinceSwing = 1;
            }
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
