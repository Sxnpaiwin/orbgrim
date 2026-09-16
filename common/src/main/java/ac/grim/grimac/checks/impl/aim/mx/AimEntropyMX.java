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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Grim-native port of MX-Project's {@code AimStatisticsCheck} + entropy core of
 * {@code AimComplexCheck} (Unlicense, Kireiko).
 *
 * <p>Why this check: Grim 2.0 ships only {@code AimModulo360} / {@code AimDuplicateLook}
 * (packet flaws). MX's statistical layer (IQR of jiff-deltas, bot-pattern duplicates,
 * z-factor outliers, Shannon entropy gating) catches humanized/rotational auras that
 * pass flaw checks but still aim inhumanly consistently. PacketEvents is used for
 * attack-gating ({@code ATTACK} / {@code INTERACT_ENTITY}), replacing MX's
 * ProtocolLib {@code UseEntityEvent}. Rotation comes from Grim's compensated
 * {@link RotationUpdate}, which is already lag-compensated — strictly better than
 * MX's raw Bukkit rotation events.</p>
 *
 * <p>Deliberate deviations from MX: Kolmogorov-Smirnov sub-check omitted in phase 1
 * (expensive, low signal alone); punish/cancel replaced by Grim's {@code flag()}/{@code reward()}
 * + decay/setback pipeline; thresholds exposed under {@code MX.*} config keys.</p>
 */
@CheckData(
        name = "AimEntropyMX",
        stableKey = "grim.aim.mx_entropy",
        description = "MX statistical aimbot detection (IQR/pattern/z-factor)",
        decay = 0.02,
        setback = 30
)
public class AimEntropyMX extends Check implements RotationListener, PacketReceiveListener {

    private static final int SAMPLES = 25;
    private static final long ATTACK_WINDOW_MS = 3500L;

    private final List<Float> yawDeltas = new ArrayList<>(SAMPLES + 4);
    private final List<Float> pitchDeltas = new ArrayList<>(SAMPLES + 4);
    private final List<Double> shannonWindow = new ArrayList<>(10);
    private final float[] buf = new float[16]; // MX buffer semantics: 0=zfactor, 8=iqr, 10=improbable

    private long lastAttackMillis = 0L;

    // MX defaults from AimStatisticsCheck.config()
    private double iqrLow = 12.5;
    private double iqrHigh = 96.0;
    private float iqrVlLimit = 11.0f;
    // Local kill-switch. Do NOT use Check.setEnabled here: PunishmentManager owns
    // that flag (Combat punish group matches "Aim", which covers this check).
    private boolean mxEnabled = true;

    public AimEntropyMX(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        iqrLow = config.getDoubleElse("MX.aim-entropy.iqr-low", 12.5);
        iqrHigh = config.getDoubleElse("MX.aim-entropy.iqr-high", 96.0);
        iqrVlLimit = (float) config.getDoubleElse("MX.aim-entropy.iqr-vl-limit", 11.0);
        mxEnabled = config.getBooleanElse("MX.aim-entropy.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            lastAttackMillis = System.currentTimeMillis();
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttackMillis = System.currentTimeMillis();
            }
        }
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!mxEnabled) return;
        // Same exemptions as Grim's own aim checks: teleport / vehicle rotation resets
        if (player.packetStateData.lastPacketWasTeleport || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation) {
            return;
        }
        if (System.currentTimeMillis() > lastAttackMillis + ATTACK_WINDOW_MS) {
            if (!yawDeltas.isEmpty()) {
                yawDeltas.clear();
                pitchDeltas.clear();
            }
            return;
        }

        float dx = rotationUpdate.deltaYaw();
        float dy = rotationUpdate.deltaPitch();
        if (dx == 0 && dy == 0) return; // MX NoRotationEvent path ignored: no signal

        yawDeltas.add(dx);
        pitchDeltas.add(dy);
        if (yawDeltas.size() >= SAMPLES) checkRaw();
    }

    private void checkRaw() {
        List<Float> x = new ArrayList<>(yawDeltas);
        List<Float> y = new ArrayList<>(pitchDeltas);

        List<Double> zFactorYaw = MXStatsUtil.getZScoreOutliers(x, 2.0);
        List<Float> jiffYaw = MXStatsUtil.getJiffDelta(x, 5);
        List<Float> jiffPitch = MXStatsUtil.getJiffDelta(y, 5);

        // --- MX omni/IQR check (AimStatisticsCheck lines ~96-118) ---
        List<Float> jiffOmni = new ArrayList<>(Math.min(jiffYaw.size(), jiffPitch.size()));
        for (int i = 0; i < Math.min(jiffYaw.size(), jiffPitch.size()); i++) {
            float denom = jiffPitch.get(i);
            jiffOmni.add(denom == 0 ? Float.POSITIVE_INFINITY : jiffYaw.get(i) / denom);
        }
        int infs = 0;
        for (float j : jiffOmni) if (Float.isInfinite(j)) infs++;
        double iqr = MXStatsUtil.getIQR(jiffOmni);
        boolean iqrFlag = false;
        if (iqr > iqrLow && iqr < iqrHigh && infs > 0) {
            addBuf(8, (iqr > 20) ? 1.4f : 0.8f);
            if (buf[8] > iqrVlLimit) {
                iqrFlag = true;
                buf[8] = iqrVlLimit - 2f;
            }
        } else if (iqr < 13 || infs == 0) {
            addBuf(8, iqr < 7 ? -5.0f : -3.5f);
        }

        // --- MX bot-pattern check (~136-154): repeated scientific-notation jiff duplicates ---
        int jiffPatterns = 0;
        for (int i = 0; i < jiffYaw.size(); i++) {
            float f = jiffYaw.get(i);
            if (!String.valueOf(f).contains("E") || f == 0) continue;
            for (int r = 0; r < jiffYaw.size(); r++) {
                if (r == i) continue;
                if (f == jiffYaw.get(r)) jiffPatterns++;
            }
        }
        double avgX = MXStatsUtil.getAverage(x);
        boolean patternFlag = jiffPatterns > 2 && avgX > 3.0
                && jiffPatterns != 4 && jiffPatterns != 6 && jiffPatterns != 12;

        // --- MX z-factor check (~157-178) ---
        boolean positive = false, negative = false;
        for (double d : zFactorYaw) {
            if (d > 10) positive = true;
            if (d < -10) negative = true;
        }
        boolean zFlag = false;
        if (zFactorYaw.size() == 2 && positive && negative && MXStatsUtil.getMax(zFactorYaw) < 55) {
            addBuf(0, 1.5f);
            if (buf[0] > 4) zFlag = true;
            if (buf[0] > 7.0f) buf[0] = 6.0f;
        } else {
            addBuf(0, -1.2f);
        }

        // --- Shannon entropy stability (MX ~126-135, alert-only there; here decay signal) ---
        shannonWindow.add(MXStatsUtil.getShannonEntropy(jiffYaw));
        if (shannonWindow.size() > 9) {
            Set<Double> uniq = new HashSet<>(shannonWindow);
            double diff = MXStatsUtil.getDifference(MXStatsUtil.getMin(uniq), MXStatsUtil.getMax(uniq));
            // Suspiciously stable entropy over 9 windows + another signal = stronger verdict
            if (uniq.size() > 3 && uniq.size() <= 5 && diff < 0.38 && (iqrFlag || zFlag || patternFlag)) {
                flag("stable entropy " + uniq.size() + " diff=" + String.format("%.3f", diff));
                shannonWindow.clear();
                yawDeltas.clear();
                pitchDeltas.clear();
                return;
            }
            shannonWindow.clear();
        }

        // --- Combined verdict (replaces MX's KS-based "total"): 2+ independent signals ---
        int total = (iqrFlag ? 1 : 0) + (patternFlag ? 1 : 0) + (zFlag ? 1 : 0);
        if (total >= 2) {
            addBuf(10, 5f);
            if (buf[10] >= 15.0f) {
                flag("iqr=" + String.format("%.2f", iqr) + " pattern=" + jiffPatterns + " z=" + zFactorYaw.size());
                buf[10] = 13.0f;
            }
        } else {
            addBuf(10, -2f);
            reward();
        }

        // Single strong signals still flag (lower weight than combined)
        if (total == 1) {
            if (iqrFlag) flag("IQR " + String.format("%.2f", iqr));
            else if (patternFlag) flag("bot-pattern " + jiffPatterns);
            else flag("z-factor " + zFactorYaw);
        }

        yawDeltas.clear();
        pitchDeltas.clear();
    }

    private void addBuf(int index, float v) {
        buf[index] = Math.max(0, buf[index] + v);
    }
}
