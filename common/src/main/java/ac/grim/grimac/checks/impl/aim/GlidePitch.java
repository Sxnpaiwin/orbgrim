package ac.grim.grimac.checks.impl.aim;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import org.jetbrains.annotations.NotNull;

/**
 * Detects silent-pitch aimbots that glide toward their target at a constant rate and
 * stop on an exact integer pitch — the cartcore pattern ({@code targetPitch} is always
 * an {@code int}, approached linearly per frame and committed per tick).
 *
 * <p>Tripwire: 8+ consecutive pitch deltas that are near-identical (within 0.1°) and
 * individually above sensitivity noise, ending with the pitch within 0.05 of an
 * integer. Zero-delta runs are explicitly excluded so standing still can never count.
 * A second qualifying run within 60s is required before the first flag, so one steady
 * mouse drag cannot page staff. Generalizes past cartcore to any constant-rate
 * pitch glide with integer snap.</p>
 *
 * <p>Version-agnostic by construction: rotation deltas only, no per-version branches.</p>
 */
@CheckData(
        name = "GlidePitch",
        stableKey = "grim.aim.glide_pitch",
        description = "Constant-rate pitch glide ending on integer pitch",
        decay = 0.05,
        setback = 25
)
public class GlidePitch extends Check implements RotationListener {

    private static final double EPSILON = 0.1;
    private static final double MIN_DELTA = 0.5;
    private static final double MAX_DELTA = 30.0;
    private static final int RUN_LENGTH = 8;
    private static final double INTEGER_SNAP = 0.05;
    private static final long CONFIRM_WINDOW_MS = 60_000L;

    private double lastDelta;
    private boolean hasLastDelta;
    private int run;
    private int confirmations;
    private long firstConfirmMs;
    private boolean enabled = true;

    public GlidePitch(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("GlidePitch.enable", true);
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!enabled) return;
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            resetRun();
            return;
        }

        double delta = rotationUpdate.deltaPitchABS();
        if (delta == 0 || delta < MIN_DELTA || delta > MAX_DELTA) {
            endRun(rotationUpdate.newPitch());
            return;
        }

        if (hasLastDelta && Math.abs(delta - lastDelta) <= EPSILON) {
            run++;
        } else {
            run = 1;
        }
        lastDelta = delta;
        hasLastDelta = true;

        if (run >= RUN_LENGTH) {
            float pitch = rotationUpdate.newPitch();
            if (Math.abs(pitch - Math.round(pitch)) <= INTEGER_SNAP) {
                long now = System.currentTimeMillis();
                if (confirmations == 0 || now - firstConfirmMs > CONFIRM_WINDOW_MS) {
                    confirmations = 1;
                    firstConfirmMs = now;
                } else if (++confirmations >= 2) {
                    flag("constant pitch glide to integer, delta=" + String.format("%.3f", delta)
                            + " run=" + run);
                    confirmations = 0;
                }
                run = 0;
            }
        }
    }

    private void endRun(float pitch) {
        // A run that breaks exactly onto an integer still counts if it was long enough.
        if (run >= RUN_LENGTH && Math.abs(pitch - Math.round(pitch)) <= INTEGER_SNAP) {
            long now = System.currentTimeMillis();
            if (confirmations == 0 || now - firstConfirmMs > CONFIRM_WINDOW_MS) {
                confirmations = 1;
                firstConfirmMs = now;
            } else if (++confirmations >= 2) {
                flag("constant pitch glide to integer, run=" + run);
                confirmations = 0;
            }
        }
        resetRun();
    }

    private void resetRun() {
        run = 0;
        hasLastDelta = false;
        lastDelta = 0;
    }
}
