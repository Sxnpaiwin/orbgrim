package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.BlockPlaceListener;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.BlockPlace;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code RotationSnapHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects snap-aim: a huge abrupt yaw flick surrounded by near-static rotations,
 * correlated with swing/attack/place activity. Base tripwire is {@code prev < 9 &&
 * last > 40 && current < 9} with recent swing/attack; the verdict weight then scales
 * with snap size (up to 120 above 360°) and multiplies on corroborating signals:
 * recent block place (×1.5), look-ray flipping onto the victim (×2), and silent
 * movement (×3). A confidence accumulator flags at 30 so single mid-range snaps never
 * punish alone — plus a scaffold-lite path for static-place + silent snaps.</p>
 *
 * <p>Deviations: Intave's WASD key-state silent-move detector has no Grim equivalent
 * (Grim brute-forces inputs instead of tracking them), so silence is estimated from
 * velocity-direction continuity — a snap with no matching turn in movement direction
 * scores SILENT, a snap that turns with the body scores CHANGED, near-stationary
 * scores NONE. Yaw motion uses wrapped angular distance (Intave's raw difference
 * double-counts ±180° wraps, which {@code AimModulo360} already owns). Thresholds and
 * multipliers are otherwise exact.</p>
 */
@CheckData(
        name = "SnapAimIV",
        stableKey = "grim.aim.iv_snap",
        description = "Snap aim with corroborating signals (Intave port)",
        decay = 0.05,
        setback = 25
)
public class SnapAimIV extends Check implements RotationListener, PacketReceiveListener, BlockPlaceListener {

    private static final long BOOST_WINDOW_MS = 150L;

    private final double[] yawMotions = new double[2];
    private final CombatState combat = new CombatState();
    private int prevMoveState;
    private float internalViolation;
    private long lastSwing;
    private long lastAttack;
    private long lastBlockPlace;
    private int rotationCounter;
    private int rotationsSinceTeleport;
    private Tick storedTick;
    private double lastX;
    private double lastZ;
    private double prevVelDir;
    private boolean hasVel;
    private boolean enabled = true;

    // KeyStates mirror: 0 NONE, 1 CHANGED, 2 SILENTMOVE
    private static final int NONE = 0;
    private static final int CHANGED = 1;
    private static final int SILENTMOVE = 2;

    public SnapAimIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.snap-aim.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            lastSwing = System.currentTimeMillis();
        } else if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            lastAttack = System.currentTimeMillis();
            combat.onAttack(new WrapperPlayClientAttack(event).getEntityId());
        } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttack = System.currentTimeMillis();
                combat.onAttack(packet.getEntityId());
            }
        }
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        lastBlockPlace = System.currentTimeMillis();
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!enabled) return;
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            rotationsSinceTeleport = 0;
            return;
        }
        rotationsSinceTeleport++;
        rotationCounter++;

        double dx = player.x - lastX;
        double dz = player.z - lastZ;
        lastX = player.x;
        lastZ = player.z;
        double speed = Math.sqrt(dx * dx + dz * dz);
        if (speed > 1e-6) {
            internalViolation = Math.max(0, internalViolation - 0.01f);
        }

        double yawMotion = Math.abs(PerfectAim.angleDistance(rotationUpdate.oldYaw(), rotationUpdate.newYaw()));

        if ((yawMotion > 40 && yawMotions[1] < 9) || (yawMotion > 25 && yawMotions[1] == 0)) {
            prevMoveState = classifyMoveState(dx, dz, speed, yawMotion);
            storedTick = new Tick(player.x, player.y, player.z,
                    rotationUpdate.oldYaw(), rotationUpdate.oldPitch());
        }

        boolean liteSuspicious = yawMotions[1] == 0 && yawMotions[0] > 25 && yawMotion < 9;
        boolean liteFlag = liteSuspicious && prevMoveState == SILENTMOVE
                && rotationCounter > 10 && rotationsSinceTeleport > 7;

        boolean snapDetected = yawMotions[1] < 9 && yawMotions[0] > 40 && yawMotion < 9
                && (recent(lastSwing) || recent(lastAttack))
                && rotationCounter > 10 && rotationsSinceTeleport > 7;

        if (snapDetected) {
            double valueOfSnap = yawMotions[0];
            String details = "yaw: [" + f(yawMotions[1]) + "/" + f(yawMotions[0]) + "/" + f(yawMotion) + "]";
            if (prevMoveState == SILENTMOVE) details += ", movement: silent";
            else if (prevMoveState == CHANGED) details += ", movement: changed";

            boolean changedLookToEntity = false;
            PerfectAim now = combat.lastTargetId() < 0 ? null : PerfectAim.compute(player,
                    combat.lastTargetId(), rotationUpdate.newYaw(), rotationUpdate.newPitch());
            if (now != null && now.valid && storedTick != null) {
                PerfectAim then = PerfectAim.computeAt(player, now.entityId,
                        storedTick.x, storedTick.y + player.getEyeHeight(), storedTick.z,
                        storedTick.yaw, storedTick.pitch);
                if (then.valid) {
                    changedLookToEntity = then.inSight != now.inSight;
                    if (changedLookToEntity) details += ", look changed to entity";
                }
            }

            double vl = calculateViolation(valueOfSnap, changedLookToEntity, liteFlag);
            addConfidence((int) vl, "rotation snapped suspiciously", details);
            liteFlag = false;
        }

        if (liteFlag) {
            addConfidence(30, "rotation snapped suspiciously while scaffolding",
                    "yaw: " + f(yawMotions[0]));
        }

        yawMotions[1] = yawMotions[0];
        yawMotions[0] = yawMotion;
    }

    /** Velocity-continuity proxy for Intave's key-state silent-move detector. */
    private int classifyMoveState(double dx, double dz, double speed, double yawMotion) {
        double velDir = Math.toDegrees(Math.atan2(-dx, dz));
        int state = NONE;
        if (hasVel && speed > 0.05 && yawMotion > 25) {
            double turn = Math.abs(wrap180(velDir - prevVelDir));
            state = turn < 45 ? SILENTMOVE : CHANGED;
        }
        prevVelDir = velDir;
        hasVel = true;
        return state;
    }

    private static double wrap180(double angle) {
        angle %= 360;
        if (angle > 180) angle -= 360;
        if (angle <= -180) angle += 360;
        return angle;
    }

    private void addConfidence(int amount, String message, String details) {
        internalViolation += amount;
        if (internalViolation >= 30) {
            internalViolation -= 30;
            flag(message + " (" + details + ")");
        }
    }

    private double calculateViolation(double valueOfSnap, boolean changedLookToEntity, boolean liteFlag) {
        double vl = 7;
        if (valueOfSnap > 360) vl = 120;
        else if (valueOfSnap > 178) vl = 50;
        else if (valueOfSnap > 90) vl = 20;
        else if (valueOfSnap > 50) vl = 10;
        if (recent(lastBlockPlace)) vl *= 1.5;
        if (changedLookToEntity) vl *= 2;
        if (prevMoveState == SILENTMOVE) vl *= 3;
        else if (prevMoveState == CHANGED) vl *= 1.7;
        if (liteFlag) vl += 10;
        vl /= 3;
        if (vl > 160 && valueOfSnap < 360) vl = 160;
        return vl;
    }

    private static boolean recent(long timestamp) {
        return System.currentTimeMillis() - timestamp < BOOST_WINDOW_MS;
    }

    private static String f(double v) {
        return String.format("%.2f", v);
    }

    private static final class Tick {
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;

        Tick(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
