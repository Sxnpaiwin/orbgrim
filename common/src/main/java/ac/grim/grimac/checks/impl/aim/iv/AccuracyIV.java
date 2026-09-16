package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code AccuracyLongTermHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects long-run near-perfect hitters: over each 80-attack window against a moving,
 * established victim, a fail rate (swings that never became attacks) below 3% with no
 * target switching means a silent/smooth aura that essentially never whiffs. Pure
 * swing-vs-attack counting — no rotation math, no VL ramp, resets every window.</p>
 *
 * <p>Deviations: victim motion/age/switch tracking via {@link CombatState} (victim counts
 * as established 10s after first seen); teleport gate uses Grim's packet state.</p>
 */
@CheckData(
        name = "AccuracyIV",
        stableKey = "grim.aim.iv_accuracy",
        description = "Inhuman long-term attack accuracy (Intave port)",
        decay = 0.05,
        setback = 25
)
public class AccuracyIV extends Check implements PacketReceiveListener {

    private static final int WINDOW_ATTACKS = 80;
    private static final double FAIL_RATE_LIMIT = 3.0;
    private static final long VICTIM_MIN_AGE_MS = 10_000L;

    private final CombatState combat = new CombatState();
    private double swings;
    private double attacks;
    private long victimFirstSeenMs = 0L;
    private int victimSeenId = -1;
    private boolean enabled = true;

    public AccuracyIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.accuracy.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || player.packetStateData.lastPacketWasTeleport) return;

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            // Intave gates counting itself: only swings inside an active accuracy
            // window (recent attack on a moving, established victim) participate.
            if (inAccuracyWindow()) swings++;
            return;
        }

        Integer attackId = attackEntityId(event);
        if (attackId == null) return;
        combat.onAttack(attackId);

        PerfectAim aim = PerfectAim.compute(player, attackId, player.yaw, player.pitch);
        if (!aim.valid || !aim.living) return;
        combat.pushVictimCenter(aim.centerX, aim.centerY, aim.centerZ);

        long now = System.currentTimeMillis();
        if (attackId != victimSeenId) {
            victimSeenId = attackId;
            victimFirstSeenMs = now;
        }
        if (!inAccuracyWindow()) return;

        attacks++;
        swings--;
        if (attacks > WINDOW_ATTACKS) {
            double failRate = (swings / attacks) * 100.0;
            if (failRate >= 0 && failRate < FAIL_RATE_LIMIT) {
                flag("maintains high attack accuracy, fail rate: " + String.format("%.2f", failRate) + "%");
            }
            attacks = 0;
            swings = 0;
        }
    }

    private boolean inAccuracyWindow() {
        if (victimSeenId < 0) return false;
        if (!combat.victimMoving(0.05)) return false;
        if (System.currentTimeMillis() - victimFirstSeenMs < VICTIM_MIN_AGE_MS) return false;
        return combat.recentlyAttacked(500) && !combat.recentlySwitched(1000);
    }

    private static Integer attackEntityId(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            return new WrapperPlayClientAttack(event).getEntityId();
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return packet.getEntityId();
            }
        }
        return null;
    }
}
