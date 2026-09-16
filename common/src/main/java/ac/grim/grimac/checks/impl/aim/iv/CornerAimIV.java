package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Grim-native port of Intave's {@code AccuracyHitboxCornerHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects rotational move-aim riding hitbox edges: a near-perfect hit rate maintained
 * while sweeping fast and aiming far from the ideal angle. Per rotation it samples the
 * distance-to-perfect-yaw and yaw speed; every 21+ samples it joins those averages with
 * the swing/attack fail rate — {@code failRate < 5% && (avgYawSpeed > 10 || avgDist > 10)}
 * flags. No single signal is suspicious alone; the triple is the tell.</p>
 *
 * <p>Deviations: Intave's accuracy counters/attack metadata replaced by local counters +
 * {@link CombatState}; perfect angles by {@link PerfectAim}; nerfs omitted (Grim
 * punishment pipeline handles consequences).</p>
 */
@CheckData(
        name = "CornerAimIV",
        stableKey = "grim.aim.iv_corner",
        description = "High accuracy while aiming at hitbox corners (Intave port)",
        decay = 0.05,
        setback = 25
)
public class CornerAimIV extends Check implements RotationListener, PacketReceiveListener {

    private final CombatState combat = new CombatState();
    private final List<Float> distList = new ArrayList<>();
    private final List<Float> speedList = new ArrayList<>();
    private double swings;
    private double attacks;
    private double vl;
    private boolean enabled = true;

    public CornerAimIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.corner-aim.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            swings++;
            return;
        }
        Integer attackId = attackEntityId(event);
        if (attackId == null) return;
        combat.onAttack(attackId);

        PerfectAim aim = PerfectAim.compute(player, attackId, player.yaw, player.pitch);
        if (aim.valid) combat.pushVictimCenter(aim.centerX, aim.centerY, aim.centerZ);

        // Intave skips counting only when the victim is known AND stationary.
        if (aim.valid && !combat.victimMoving(0.05)) return;
        if (!combat.recentlyAttacked(500) || combat.recentlySwitched(1000)) return;

        attacks++;
        swings--;
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!enabled) return;
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            return;
        }
        if (combat.lastTargetId() < 0 || !combat.recentlyAttacked(1000)
                || combat.recentlySwitched(500)) {
            return;
        }

        PerfectAim aim = PerfectAim.compute(player, combat.lastTargetId(),
                rotationUpdate.newYaw(), rotationUpdate.newPitch());
        if (!aim.valid || !aim.living || aim.reach < 1.0) return;

        float distPerfect = Math.abs(PerfectAim.angleDistance(rotationUpdate.newYaw(), aim.perfectYaw));
        float yawSpeed = rotationUpdate.deltaYawABS();
        float pitchSpeed = rotationUpdate.deltaPitchABS();

        if (distList.size() > 20) {
            double distAvg = average(distList);
            double speedAvg = average(speedList);
            double failRate = attacks == 0 ? 100.0 : (swings / attacks) * 100.0;

            if (failRate < 5 && (speedAvg > 10 || distAvg > 10)) {
                vl++;
                flag("maintains high attack accuracy whilst aiming at hitbox corners, fail: "
                        + String.format("%.2f", failRate) + "%, rotation: "
                        + String.format("%.2f", speedAvg) + ", distance: "
                        + String.format("%.2f", distAvg));
            } else if (vl > 0) {
                vl -= 0.2;
            }

            attacks = 0;
            swings = 0;
            distList.clear();
            speedList.clear();
        }

        if (yawSpeed > 5 && combat.recentlyAttacked(60)) {
            distList.add(distPerfect);
            speedList.add(yawSpeed + pitchSpeed);
        }
    }

    private static double average(List<Float> data) {
        double sum = 0;
        for (float f : data) sum += f;
        if (sum == 0) return 0;
        return sum / data.size();
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
