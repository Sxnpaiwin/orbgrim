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
 * Grim-native port of Intave's {@code RotationStandardDeviationHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects aimbot lock-on against a moving target through inhumanly consistent tracking
 * error: while rotating fast it buffers the distance-to-perfect-aim and flags tiny
 * population standard deviation — yaw samples over windows of 7 (stddev &lt; 1.0, three
 * consecutive) and pitch over windows of 10 (stddev &lt; 3.0, five consecutive). A human
 * tracking a strafing victim cannot hold aim error this steady.</p>
 *
 * <p>Deviations: perfect angles via {@link PerfectAim}; victim motion via
 * {@link CombatState}; nerfs omitted.</p>
 */
@CheckData(
        name = "AimStdevIV",
        stableKey = "grim.aim.iv_stdev",
        description = "Inhumanly consistent tracking error (Intave port)",
        decay = 0.05,
        setback = 25
)
public class AimStdevIV extends Check implements RotationListener, PacketReceiveListener {

    private final CombatState combat = new CombatState();
    private final List<Float> yawDists = new ArrayList<>();
    private final List<Float> pitchDists = new ArrayList<>();
    private double balanceYaw;
    private double balancePitch;
    private boolean enabled = true;

    public AimStdevIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.aim-stdev.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            combat.onAttack(new WrapperPlayClientAttack(event).getEntityId());
        } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                combat.onAttack(packet.getEntityId());
            }
        }
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!enabled) return;
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            return;
        }
        if (combat.lastTargetId() < 0 || !combat.recentlyAttacked(500)) return;

        PerfectAim aim = PerfectAim.compute(player, combat.lastTargetId(),
                rotationUpdate.newYaw(), rotationUpdate.newPitch());
        if (!aim.valid || !aim.living) return;
        combat.pushVictimCenter(aim.centerX, aim.centerY, aim.centerZ);
        if (!combat.victimMoving(0.05)) return;

        float yawSpeed = rotationUpdate.deltaYawABS();
        float distYaw = Math.abs(PerfectAim.angleDistance(rotationUpdate.newYaw(), aim.perfectYaw));
        if (yawSpeed > 2.6f) yawDists.add(distYaw);
        if (yawDists.size() >= 7) {
            if (stddev(yawDists) < 1.0) {
                if (balanceYaw++ >= 2) {
                    flag("rotation standard deviation is too low");
                    balanceYaw--;
                }
            } else if (balanceYaw > 0) {
                balanceYaw -= 0.2;
            }
            yawDists.clear();
        }

        float pitchSpeed = rotationUpdate.deltaPitchABS();
        float distPitch = Math.abs(rotationUpdate.newPitch() - aim.perfectPitch);
        if (pitchSpeed > 0.5f && yawSpeed > 3f) pitchDists.add(distPitch);
        if (pitchDists.size() >= 10) {
            if (stddev(pitchDists) < 3.0) {
                if (balancePitch++ >= 4) {
                    flag("rotation standard deviation is too low");
                    balancePitch -= 2;
                }
            } else if (balancePitch > 0) {
                balancePitch -= 0.2;
            }
            pitchDists.clear();
        }
    }

    private static double stddev(List<Float> data) {
        double sum = 0;
        for (float f : data) sum += f;
        double mean = sum / data.size();
        double var = 0;
        for (float f : data) var += (f - mean) * (f - mean);
        return Math.sqrt(var / data.size());
    }
}
