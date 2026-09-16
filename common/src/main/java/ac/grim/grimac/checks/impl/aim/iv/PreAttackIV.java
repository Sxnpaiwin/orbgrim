package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code PreAttackHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects fully automated combat over the long term: every tick with a swing onto a
 * moving, in-range victim but no attack counts as a pre-attack, every attack counts as
 * an attack, and every 100 attacks a pre-attack share below 4% flags — humans constantly
 * whiff aim-only swings, bots essentially never do. The instant missing-packet case is
 * {@link AttackRequiredIV}'s job; this is the slow-burn counterpart.</p>
 */
@CheckData(
        name = "PreAttackIV",
        stableKey = "grim.combat.iv_pre_attack",
        description = "Too few aim-only swings per 100 attacks (Intave port)",
        decay = 0.05,
        setback = 25
)
public class PreAttackIV extends Check implements PacketReceiveListener {

    private static final int WINDOW_ATTACKS = 100;
    private static final int PRE_ATTACK_LIMIT = 4;
    private static final long VICTIM_MIN_AGE_MS = 10_000L;

    private final CombatState combat = new CombatState();
    private boolean swung;
    private boolean attackedTick;
    private double preAttacks;
    private double attacks;
    private long victimFirstSeenMs;
    private int victimSeenId = -1;
    private boolean enabled = true;

    public PreAttackIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.pre-attack.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            swung = true;
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            onAttack(new WrapperPlayClientAttack(event).getEntityId());
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack(packet.getEntityId());
            }
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;
        try {
            if (player.packetStateData.lastPacketWasTeleport) return;
            if (!swung || combat.lastTargetId() < 0 || holdingRod()) return;

            PerfectAim aim = PerfectAim.computeExpanded(player, combat.lastTargetId(),
                    player.yaw, player.pitch, 0.25);
            if (!aim.valid || !aim.living) return;
            combat.pushVictimCenter(aim.centerX, aim.centerY, aim.centerZ);
            if (!combat.victimMoving(0.1) || aim.reach < 1.0) return;
            long now = System.currentTimeMillis();
            if (combat.lastTargetId() != victimSeenId) {
                victimSeenId = combat.lastTargetId();
                victimFirstSeenMs = now;
            }
            if (now - victimFirstSeenMs < VICTIM_MIN_AGE_MS) return;

            if (!attackedTick && aim.inSight) preAttacks++;
            if (attackedTick) {
                attacks++;
                if (attacks >= WINDOW_ATTACKS) {
                    if (preAttacks < PRE_ATTACK_LIMIT) {
                        flag("attacks seem automated, " + (int) preAttacks + " pre-swings per 100");
                    }
                    attacks = 0;
                    preAttacks = 0;
                }
            }
        } finally {
            swung = false;
            attackedTick = false;
        }
    }

    private void onAttack(int entityId) {
        attackedTick = true;
        combat.onAttack(entityId);
    }

    private boolean holdingRod() {
        try {
            return player.inventory.getItemInHand(InteractionHand.MAIN_HAND).getType() == ItemTypes.FISHING_ROD;
        } catch (Exception e) {
            return false;
        }
    }
}
