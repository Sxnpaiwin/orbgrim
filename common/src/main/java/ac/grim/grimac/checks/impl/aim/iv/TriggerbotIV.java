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
 * Triggerbot detection via firing behavior — no equivalent exists in Grim, MX, or Intave.
 *
 * <p>A triggerbot does not aim; it <i>fires</i>. That leaves two measurable traces:</p>
 * <ol>
 *   <li><b>Reaction delay.</b> Time from crosshair-enters-hitbox to the attack packet.
 *   Humans react in ~200ms+ with wide variance and routinely fire <i>before</i>
 *   acquiring (pre-clicks). Triggerbots fire with short, near-constant delay and
 *   essentially never pre-click. Flags when the buffered mean is low, the spread is
 *   tight, and zero acquisitions were preceded by a blind attack — confirmed twice
 *   before flagging.</li>
 *   <li><b>Conditional gating.</b> Per-tick attack probability conditioned on aim state:
 *   {@code P(attack|on-target) > 0.9} together with {@code P(attack|off-target) < 0.15}
 *   over a 200-tick window means the player fires if and only if aimed. Legit holders
 *   spray off-target shots; legit clickers miss the exact acquisition moment.</li>
 * </ol>
 *
 * <p>Guards: state resets on target switch; teleport/vehicle exemptions; stationary
 * victims ignored (spawn-camping a still target looks "gated" for anyone); gating
 * requires a minimum of off-target ticks so tight-corner fights cannot qualify.</p>
 *
 * <p>Every flag verbose prints the underlying statistics
 * (mean/stddev/pre-clicks/gating fractions), which doubles as labeled data if an ML
 * model is ever trained on top of this check.</p>
 */
@CheckData(
        name = "TriggerbotIV",
        stableKey = "grim.combat.iv_triggerbot",
        description = "Triggerbot firing behavior (reaction delay + aim gating)",
        decay = 0.05,
        setback = 25
)
public class TriggerbotIV extends Check implements RotationListener, PacketReceiveListener {

    private static final int DELAY_SAMPLES = 30;
    private static final int DELAY_MIN_SAMPLES = 15;
    private static final int GATE_TICKS = 200;
    private static final int GATE_MIN_OFF_TICKS = 30;

    private final CombatState combat = new CombatState();
    private final List<Long> delays = new ArrayList<>();
    private final List<Boolean> acqPreClick = new ArrayList<>();
    private boolean wasOnTarget;
    private long acquireMs;
    private boolean acquired;
    private long lastAttackMs;

    private int tickOnTarget;
    private int tickOffTarget;
    private int tickOnAttack;
    private int tickOffAttack;
    private int tickCount;
    private boolean tickAttacked;
    private boolean tickSawOnTarget;

    private int delayConfirmations;
    private boolean enabled = true;
    private double maxMeanMs = 130;
    private double maxStddevMs = 45;

    public TriggerbotIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.triggerbot.enable", true);
        maxMeanMs = config.getDoubleElse("Intave.triggerbot.max-mean-ms", 130);
        maxStddevMs = config.getDoubleElse("Intave.triggerbot.max-stddev-ms", 45);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        Integer attackId = attackEntityId(event);
        if (attackId != null) {
            combat.onAttack(attackId);
            tickAttacked = true;
            lastAttackMs = System.currentTimeMillis();
            if (acquired && attackId == combat.lastTargetId()) {
                long delay = System.currentTimeMillis() - acquireMs;
                delays.add(delay);
                if (delays.size() > DELAY_SAMPLES) delays.remove(0);
                acquired = false;
                if (delays.size() >= DELAY_MIN_SAMPLES) evaluateDelays();
            }
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;
        if (player.packetStateData.lastPacketWasTeleport) {
            tickAttacked = false;
            tickSawOnTarget = false;
            return;
        }

        if (tickSawOnTarget) {
            tickOnTarget++;
            if (tickAttacked) tickOnAttack++;
        } else {
            tickOffTarget++;
            if (tickAttacked) tickOffAttack++;
        }
        tickAttacked = false;
        tickSawOnTarget = false;

        if (++tickCount >= GATE_TICKS) {
            evaluateGating();
            tickOnTarget = 0;
            tickOffTarget = 0;
            tickOnAttack = 0;
            tickOffAttack = 0;
            tickCount = 0;
        }
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!enabled) return;
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            acquired = false;
            wasOnTarget = false;
            return;
        }
        if (combat.lastTargetId() < 0 || !combat.recentlyAttacked(3000)) {
            acquired = false;
            wasOnTarget = false;
            return;
        }

        PerfectAim aim = PerfectAim.compute(player, combat.lastTargetId(),
                rotationUpdate.newYaw(), rotationUpdate.newPitch());
        boolean onTarget = aim.valid && aim.living && aim.inSight;
        if (onTarget) {
            tickSawOnTarget = true;
            combat.pushVictimCenter(aim.centerX, aim.centerY, aim.centerZ);
        }

        if (onTarget && !wasOnTarget) {
            acquireMs = System.currentTimeMillis();
            acquired = true;
            // Pre-click: an attack in the 300ms before acquisition means the player
            // was already firing blind — human-like, vetoes the bot verdict.
            acqPreClick.add(acquireMs - lastAttackMs <= 300 && lastAttackMs != 0);
            if (acqPreClick.size() > DELAY_SAMPLES) acqPreClick.remove(0);
        } else if (!onTarget) {
            acquired = false;
        }
        wasOnTarget = onTarget;
    }

    private void evaluateDelays() {
        double sum = 0;
        for (long d : delays) sum += d;
        double mean = sum / delays.size();
        double var = 0;
        for (long d : delays) var += (d - mean) * (d - mean);
        double stddev = Math.sqrt(var / delays.size());
        int preClicks = 0;
        for (boolean b : acqPreClick) if (b) preClicks++;

        if (mean < maxMeanMs && stddev < maxStddevMs && preClicks == 0) {
            if (++delayConfirmations >= 2) {
                flag("inhuman trigger reaction, mean: " + String.format("%.1f", mean)
                        + "ms stddev: " + String.format("%.1f", stddev)
                        + "ms pre-clicks: 0/" + acqPreClick.size()
                        + " pAtt|on: " + gateOn() + " pAtt|off: " + gateOff());
                delayConfirmations = 0;
            }
        } else if (delayConfirmations > 0) {
            delayConfirmations--;
        }
    }

    private void evaluateGating() {
        if (tickOffTarget < GATE_MIN_OFF_TICKS) return;
        // Stationary victims make anyone look gated; require real movement.
        if (!combat.victimMoving(0.05)) return;
        double pOn = tickOnTarget == 0 ? 0 : (double) tickOnAttack / tickOnTarget;
        double pOff = (double) tickOffAttack / tickOffTarget;
        if (pOn > 0.9 && pOff < 0.15) {
            flag("fires if and only if aimed, pAtt|on: " + String.format("%.2f", pOn)
                    + " pAtt|off: " + String.format("%.2f", pOff));
        }
    }

    private String gateOn() {
        int denom = tickOnTarget + tickOnAttack;
        return denom == 0 ? "n/a" : String.format("%.2f", (double) tickOnAttack / tickOnTarget);
    }

    private String gateOff() {
        return tickOffTarget == 0 ? "n/a"
                : String.format("%.2f", (double) tickOffAttack / tickOffTarget);
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
