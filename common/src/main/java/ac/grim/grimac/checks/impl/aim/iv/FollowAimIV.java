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
 * Grim-native port of Intave's {@code RotationAccuracyYawHeuristic}
 * (source-available, intave/intave) — all six sub-detectors.
 *
 * <p>Detects aimbot-maintained yaw tracking of a moving victim: (a) extreme snap speed,
 * (b) follow-balance accumulation while close to perfect, (c) short-term accuracy
 * balance, (d) 51 consecutive precise rotations, (e) hitbox-corner stickiness while the
 * attacker moves, (f) 40-sample average/max-ratio analysis. Each targets a different
 * flavor of lock-on; together they cover smooth, silent, and snap-assisted tracking.</p>
 *
 * <p>Deviations: perfect angles/reach via {@link PerfectAim}; victim motion via
 * {@link CombatState}; attacker motion tracked locally; nerfs omitted.</p>
 */
@CheckData(
        name = "FollowAimIV",
        stableKey = "grim.aim.iv_follow",
        description = "Impossibly precise yaw tracking of moving victim (Intave port)",
        decay = 0.05,
        setback = 25
)
public class FollowAimIV extends Check implements RotationListener, PacketReceiveListener {

    private final CombatState combat = new CombatState();
    private final List<Double> yawSpeeds = new ArrayList<>();
    private final List<Double> dists = new ArrayList<>();
    private double balanceYawAccuracy;
    private double balanceYawAccuracyOther;
    private double rotationAccuracyVL;
    private double followBalance;
    private double snapVL;
    private int lastBodyDirection;
    private int cornerBalance;
    private float prevDist;
    private double lastPlayerX;
    private double lastPlayerZ;
    private double lastOwnMotion;
    private boolean hasLastPos;
    private boolean enabled = true;

    public FollowAimIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.follow-aim.enable", true);
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

        float yaw = rotationUpdate.newYaw();
        float yawSpeed = rotationUpdate.deltaYawABS();

        PerfectAim aim = PerfectAim.compute(player, combat.lastTargetId(), yaw, rotationUpdate.newPitch());
        float dist = 180f;
        double reach = 0;
        boolean living = false;
        if (aim.valid) {
            dist = Math.abs(PerfectAim.angleDistance(yaw, aim.perfectYaw));
            reach = aim.reach;
            living = aim.living;
            combat.pushVictimCenter(aim.centerX, aim.centerY, aim.centerZ);
        }
        boolean victimMoving = combat.victimMoving(0.05);

        checkSnap(yawSpeed, reach);
        if (victimMoving && yawSpeed > 1.0f) {
            checkFollow(yawSpeed, dist);
            checkShortTerm(yawSpeed, dist);
            checkLongTerm(dist);
        }
        if (living) {
            checkCorners(yaw, aim.valid ? aim.perfectYaw : yaw, dist, yawSpeed, victimMoving, reach);
            checkAvg(yawSpeed, dist, victimMoving);
        }

        double dx = player.x - lastPlayerX;
        double dz = player.z - lastPlayerZ;
        lastPlayerX = player.x;
        lastPlayerZ = player.z;
        hasLastPos = true;
        lastOwnMotion = Math.sqrt(dx * dx + dz * dz);
    }

    private void checkSnap(float yawSpeed, double reach) {
        if (combat.recentlyAttacked(150) && yawSpeed > 1001 && reach > 1.0
                && !combat.recentlySwitched(200)) {
            if (snapVL++ > 0) flag("suspicious rotation snap, yaw speed: " + String.format("%.2f", yawSpeed));
        } else if (snapVL > 0) {
            snapVL -= 0.1;
        }
    }

    private void checkFollow(float yawSpeed, float dist) {
        if (yawSpeed < 3.0f) return;
        double increase = clamp((2.2 - dist) * Math.min(6, yawSpeed), -2.5, 2);
        followBalance = Math.max(0, followBalance + increase);
        if (followBalance > 25) {
            flag("follows entity movement too precisely");
            followBalance -= 7;
        }
    }

    private void checkShortTerm(float yawSpeed, float dist) {
        if (yawSpeed < 3.0f) return;
        balanceYawAccuracy = Math.max(0, balanceYawAccuracy + 2.0 - (dist / 0.8));
        if ((int) balanceYawAccuracy > 8) {
            if (rotationAccuracyVL++ > 3) {
                flag("maintains high yaw accuracy, vl: " + (int) balanceYawAccuracy);
            }
        } else if (rotationAccuracyVL > 0) {
            rotationAccuracyVL -= 0.005;
        }
    }

    private void checkLongTerm(float dist) {
        if (dist > 4.0f) {
            balanceYawAccuracyOther = 0;
        } else if (balanceYawAccuracyOther++ > 50) {
            flag("maintains high yaw accuracy, " + (int) balanceYawAccuracyOther + " rotations");
            balanceYawAccuracyOther = 0;
        }
    }

    private void checkCorners(float yaw, float perfectYaw, float dist, float yawSpeed,
                              boolean victimMoving, double reach) {
        if (!hasLastPos || lastOwnMotion < 0.05 || reach < 1 || !victimMoving) return;
        int direction = perfectYaw > yaw ? 1 : 0;
        if (lastBodyDirection != direction) {
            cornerBalance = 0;
        } else if (yawSpeed > 3 && !player.inVehicle()) {
            float deviation = Math.abs(prevDist - dist);
            double increase = clamp((1 - deviation) * 4, -0.2, 4);
            cornerBalance = (int) clamp(cornerBalance + increase, 0, 100);
            if (cornerBalance > 30) {
                flag("maintains high yaw accuracy on hitbox corners");
                cornerBalance -= 20;
            }
        }
        lastBodyDirection = direction;
        prevDist = dist;
    }

    private void checkAvg(float yawSpeed, float dist, boolean victimMoving) {
        if (yawSpeeds.size() > 40) {
            double yawAvg = avg(yawSpeeds);
            double maxDist = dists.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double avgRatio = yawAvg / avg(dists);
            // Deliberately no zero-guard: Intave divides raw, so 0/0 stays NaN
            // (no flag) and x/0 stays infinite (flags) — both intended.
            double maxRatio = maxDist / yawAvg;
            if (maxRatio < 2 && maxDist < 30) {
                flag("rotated suspiciously, ratio: " + String.format("%.4f", maxRatio)
                        + ", maximum distance: " + String.format("%.4f", maxDist));
            }
            if (yawAvg >= 3.5 && maxDist <= 12.5 && avgRatio > 1) {
                flag("maintains precise yaw rotations, average: " + String.format("%.4f", yawAvg));
            }
            dists.clear();
            yawSpeeds.clear();
        }
        if (victimMoving) {
            dists.add((double) dist);
            yawSpeeds.add((double) yawSpeed);
        }
    }

    private static double avg(List<Double> data) {
        double sum = 0;
        for (double d : data) sum += d;
        if (sum == 0 || data.isEmpty()) return 0;
        return sum / data.size();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
