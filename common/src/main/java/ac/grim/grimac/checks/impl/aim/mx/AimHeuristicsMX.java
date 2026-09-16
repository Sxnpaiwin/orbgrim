package ac.grim.grimac.checks.impl.aim.mx;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Grim-native port of MX-Project's {@code AimBasicCheck} (Unlicense, Kireiko) —
 * the deterministic heuristic half of {@code AimHeuristicCheck}.
 *
 * <p>Detects: robotized aim-assist deltas (near-identical yaw steps), constant-rotation
 * auras, snap pattern (alternating large +/- corrections), and interpolation flaws
 * (infinite yawChange/robotized quotients). Runs on Grim's compensated
 * {@link RotationUpdate} and gates on attack packets via PacketEvents, replacing MX's
 * ProtocolLib {@code RotationEvent}/{@code UseEntityEvent} pair.</p>
 */
@CheckData(
        name = "AimHeuristicsMX",
        stableKey = "grim.aim.mx_heuristics",
        description = "MX heuristic aimbot detection (robotized/constant/snap)",
        decay = 0.03,
        setback = 25
)
public class AimHeuristicsMX extends Check implements RotationListener, PacketReceiveListener {

    private static final int SAMPLES = 10;
    private static final long ATTACK_WINDOW_MS = 3500L;

    private final List<Float> yaws = new ArrayList<>(SAMPLES + 2);
    private final List<Float> pitches = new ArrayList<>(SAMPLES + 2);

    private long lastAttackMillis = 0L;
    private float vl = 0;
    private int snapStreak = 0;

    private int robotizedThreshold = 8;
    private int constantThreshold = 6;
    private float vlLimit = 400f;
    // Local kill-switch. Do NOT use Check.setEnabled here: PunishmentManager owns
    // that flag (Combat punish group matches "Aim", which covers this check).
    private boolean mxEnabled = true;

    public AimHeuristicsMX(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        robotizedThreshold = config.getIntElse("MX.aim-heuristics.robotized-threshold", 8);
        constantThreshold = config.getIntElse("MX.aim-heuristics.constant-threshold", 6);
        vlLimit = (float) config.getDoubleElse("MX.aim-heuristics.vl-limit", 400.0);
        mxEnabled = config.getBooleanElse("MX.aim-heuristics.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            lastAttackMillis = System.currentTimeMillis();
        } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttackMillis = System.currentTimeMillis();
            }
        }
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!mxEnabled) return;
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            return;
        }
        if (System.currentTimeMillis() > lastAttackMillis + ATTACK_WINDOW_MS) {
            if (!yaws.isEmpty()) {
                yaws.clear();
                pitches.clear();
            }
            return;
        }
        // Grim already gives absolute yaw/pitch; MX's component stored absolute rotations
        yaws.add(rotationUpdate.newYaw());
        pitches.add(rotationUpdate.newPitch());
        if (yaws.size() >= SAMPLES) checkDefaultAim();
    }

    /**
     * Mirrors {@code AimBasicCheck.checkDefaultAim()} counting logic; punish calls
     * replaced by local VL accumulation + Grim {@code flag()}.
     */
    private void checkDefaultAim() {
        double oldYawResult = yaws.get(0);
        double oldPitchResult = pitches.get(0);
        double oldYawChange = Math.abs(yaws.get(0) - oldYawResult);
        double yawChangeFirst = Math.abs(yaws.get(0) - yaws.get(1));

        int machineKnownMovement = 0, constantRotations = 0, aggressiveAim = 0;
        int aggressivePatternI = 0, aggressivePatternD = 0;
        int aggressivePatternI2 = 0, aggressivePatternD2 = 0;
        int robotizedAmount = 0, infinitives = 0;

        for (int i = 0; i < yaws.size(); i++) {
            double yawChange = Math.abs(yaws.get(i) - oldYawResult);
            double pitchChange = Math.abs(pitches.get(i) - oldPitchResult);
            double robotized = Math.abs(yawChange - yawChangeFirst);
            double diff = yawChange - oldYawChange;

            if (robotized < 2 && yawChange > 2.5) robotizedAmount++;
            if (robotized < 0.99 && yawChange > 4) machineKnownMovement++;
            if (robotized < 0.02 && yawChange > 3) constantRotations++;
            if (robotized < 2 && yawChange > 3) aggressiveAim++;

            double interpolation = robotized == 0
                    ? (yawChange > 0 ? Double.POSITIVE_INFINITY : 0)
                    : yawChange / robotized;
            if (Double.isInfinite(interpolation) && yawChange > 0) {
                infinitives++;
                if (infinitives > 1 && yawChange < 0.4) infinitives--;
            }
            if (diff > 0.01 && diff < 2) aggressivePatternI++;
            if (diff < -0.01 && diff > -2) aggressivePatternD++;
            if (diff > 2) aggressivePatternI2++;
            if (diff < -2) aggressivePatternD2++;

            oldYawResult = yaws.get(i);
            oldPitchResult = pitches.get(i);
            oldYawChange = yawChange;
        }

        double avgYawStep = MXStatsUtil.getAverage(absSteps(yaws));
        boolean flagNow = false;
        String reason = "";

        if (machineKnownMovement > robotizedThreshold) {
            vl += 100;
            reason = "heuristic(aim) m=" + machineKnownMovement;
            flagNow = true;
        }
        if (constantRotations > constantThreshold) {
            vl += 65;
            reason = "heuristic(constant) c=" + constantRotations;
            flagNow = true;
        }
        if (infinitives > 1 && Math.abs(avgYawStep) > 3.2) {
            vl += 55;
            reason = "heuristic(interpolation) inf=" + infinitives;
            flagNow = true;
        }
        if (aggressivePatternI > 3 && aggressivePatternD > 3) {
            vl += 25;
            reason = "pattern(random) i=" + aggressivePatternI + " d=" + aggressivePatternD;
            flagNow = true;
        }
        if (aggressivePatternI2 > 3 && aggressivePatternD2 > 3
                && (aggressivePatternI2 + aggressivePatternD2) > 8) {
            snapStreak++;
            if (snapStreak > 2) {
                vl += 55;
                reason = "pattern(snap) streak=" + snapStreak;
                flagNow = true;
            }
        } else {
            snapStreak = 0;
        }

        vl = Math.max(0, vl - 5); // MX fade
        if (vl > vlLimit && flagNow) {
            flag(reason + " vl=" + String.format("%.0f", vl));
            vl = 360; // MX reset semantics
        } else if (!flagNow) {
            reward();
        }

        yaws.clear();
        pitches.clear();
    }

    private static List<Double> absSteps(List<Float> abs) {
        List<Double> steps = new ArrayList<>(Math.max(0, abs.size() - 1));
        for (int i = 1; i < abs.size(); i++) {
            steps.add((double) Math.abs(abs.get(i) - abs.get(i - 1)));
        }
        return steps;
    }
}
