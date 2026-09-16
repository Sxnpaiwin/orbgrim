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

/**
 * Grim-native port of Intave's {@code RotationExactHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects killauras sending mathematically perfect rotations: if the player is rotating
 * ({@code yawSpeed > 1}) yet their angle is bit-exact equal to the server-computed ideal
 * angle to a moving victim ({@code distanceToPerfect == 0}), no human mouse produced it.
 * Same for pitch. The float {@code == 0} comparison is the whole check — exactness is
 * essentially impossible by coincidence, so there is no VL ramp.</p>
 *
 * <p>Deviations: Intave's {@code perfectYaw/perfectClosestYaw/perfectPitch} feed replaced
 * by {@link PerfectAim}; teleport gate uses Grim's packet state.</p>
 */
@CheckData(
        name = "ExactAimIV",
        stableKey = "grim.aim.iv_exact",
        description = "Sent mathematically perfect attack rotation (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ExactAimIV extends Check implements RotationListener, PacketReceiveListener {

    private final CombatState combat = new CombatState();
    private boolean enabled = true;

    public ExactAimIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.exact-aim.enable", true);
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
        if (combat.lastTargetId() < 0 || !combat.recentlyAttacked(1000)) return;

        PerfectAim aim = PerfectAim.compute(player, combat.lastTargetId(),
                rotationUpdate.newYaw(), rotationUpdate.newPitch());
        if (!aim.valid || !aim.living) return;
        combat.pushVictimCenter(aim.centerX, aim.centerY, aim.centerZ);
        if (!combat.victimMoving(0.05)) return;

        float yaw = rotationUpdate.newYaw();
        float pitch = rotationUpdate.newPitch();

        if (rotationUpdate.deltaYawABS() > 1.0f) {
            float distPerfect = Math.abs(PerfectAim.angleDistance(yaw, aim.perfectYaw));
            float distClosest = Math.abs(PerfectAim.angleDistance(yaw, aim.closestYaw));
            if (distPerfect == 0 || distClosest == 0) {
                flag("sent exact yaw rotation");
                return;
            }
        }

        if (rotationUpdate.deltaPitchABS() > 1.0f
                && Math.abs(pitch - aim.perfectPitch) == 0) {
            flag("sent exact pitch rotation");
        }
    }
}
