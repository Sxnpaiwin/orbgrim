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

/**
 * Grim-native port of Intave's {@code RotationSensitivityHeuristic}
 * (source-available, intave/intave, de.jpx3.intave.check.combat.heuristics).
 *
 * <p>A vanilla client quantizes mouse movement through its sensitivity GCD, so consecutive
 * pitch deltas share a stable GCD. Aimbot/freelook rotations injected outside the mouse
 * path break that consistency. Per rotation packet this check folds the current pitch
 * delta into a running Euclidean GCD and accumulates VL whenever the GCD jumps, flagging
 * once it drifts far enough — the exact upstream algorithm, thresholds included.</p>
 *
 * <p>Deviations: ProtocolLib LOOK/POSITION_LOOK + Intave movement metadata replaced by
 * Grim's compensated {@link RotationUpdate}; teleport gate uses Grim's packet state;
 * attack recency uses ATTACK/INTERACT_ENTITY packets via PacketEvents.</p>
 */
@CheckData(
        name = "SensitivityMX",
        stableKey = "grim.aim.mx_sensitivity",
        description = "Pitch GCD inconsistent with vanilla mouse sensitivity (Intave port)",
        decay = 0.02,
        setback = 30
)
public class SensitivityMX extends Check implements RotationListener, PacketReceiveListener {

    private static final long ATTACK_WINDOW_MS = 200L;
    private static final double GCD_EPSILON = 0.001;
    private static final int VL_FLAG = 400;
    private static final int VL_RESET = 300;

    private float prevPitchGCD;
    private int sensitivityVL;
    private long lastAttackMillis = 0L;
    private boolean mxEnabled = true;

    public SensitivityMX(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        mxEnabled = config.getBooleanElse("Intave.sensitivity.enable", true);
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
        // Intave: only evaluate aim immediately around attacks.
        if (System.currentTimeMillis() > lastAttackMillis + ATTACK_WINDOW_MS) return;

        float pitchDifference = rotationUpdate.deltaPitchABS();
        if (pitchDifference == 0) return;

        float prev = prevPitchGCD == 0 ? pitchDifference : prevPitchGCD;
        double pitchA = prev;
        double pitchB = pitchDifference;
        double pitchR;
        int pitchCountdown = 100;

        while ((pitchR = pitchA % pitchB) > Math.max(pitchA, pitchB) * 1e-3) {
            pitchA = pitchB;
            pitchB = pitchR;
            if (pitchCountdown-- < 0) break;
        }

        float pitchGCD = (float) pitchB;
        double gcdDifference = Math.abs(pitchGCD - prevPitchGCD);
        prevPitchGCD = pitchGCD;

        if (gcdDifference > GCD_EPSILON) {
            if (pitchDifference > 1.0f) {
                sensitivityVL += pitchDifference > 5 ? 10 : 5;
            }
            if ((int) Math.round(sensitivityVL / 2d) % 50 == 0 && sensitivityVL > 0
                    && sensitivityVL >= VL_FLAG) {
                flag("rotations are out of sync, gcd vl: " + sensitivityVL);
                sensitivityVL = VL_RESET;
            }
        } else if (sensitivityVL > 0) {
            sensitivityVL--;
        }
    }
}
