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
 * Grim-native port of Intave's {@code RotationModuloResetHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects yaw-modulo/reset exploits where the client wraps yaw to snap without a large
 * visible delta: stage one arms when a 100°+ raw jump (both sides within ±360°) lands
 * while the victim is in line of sight shortly after an attack; stage two fires on the
 * next rotation if the victim is still in sight. Covers the 100–320° reset window below
 * {@code AimModulo360}'s tripwire.</p>
 *
 * <p>Deviations: line-of-sight via {@link PerfectAim#inSight}; Intave's 100-tick
 * post-teleport gate approximated by 100 observed rotations since Grim only exposes the
 * teleport tick itself.</p>
 */
@CheckData(
        name = "ModuloResetIV",
        stableKey = "grim.aim.iv_modulo_reset",
        description = "Yaw-modulo rotation reset exploit (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ModuloResetIV extends Check implements RotationListener, PacketReceiveListener {

    private final CombatState combat = new CombatState();
    private boolean armed;
    private int rotationsSinceTeleport;
    private boolean enabled = true;

    public ModuloResetIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.modulo-reset.enable", true);
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
            rotationsSinceTeleport = 0;
            armed = false;
            return;
        }
        rotationsSinceTeleport++;
        if (combat.lastTargetId() < 0 || combat.recentlySwitched(5000)
                || rotationsSinceTeleport < 100) {
            armed = false;
            return;
        }

        float yaw = rotationUpdate.newYaw();
        float lastYaw = rotationUpdate.oldYaw();

        PerfectAim aim = PerfectAim.compute(player, combat.lastTargetId(), yaw, rotationUpdate.newPitch());
        if (!aim.valid) {
            armed = false;
            return;
        }

        if (armed) {
            if (aim.inSight && lastYaw != 0) {
                flag("possible rotation reset");
            }
            armed = false;
            return;
        }

        if (combat.recentlyAttacked(1000) && aim.reach > 1.0
                && isSuspiciousYawJump(yaw, lastYaw) && aim.inSight) {
            armed = true;
        }
    }

    static boolean isSuspiciousYawJump(float yaw, float lastYaw) {
        float receivedDistance = Math.abs(yaw - lastYaw);
        boolean roundingConditions = Math.abs(yaw) <= 360 && Math.abs(lastYaw) <= 360;
        return roundingConditions && receivedDistance > 100;
    }
}
