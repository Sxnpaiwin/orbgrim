package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * FakeLag / blink-combat detection: catching clients that queue movement packets and
 * release them in bursts (e.g. LiquidBounce FakeLag, 300–600ms windows) while attacks
 * go out immediately.
 *
 * <p>Three sub-detectors share one movement-gap tracker:</p>
 * <ol>
 *   <li><b>Gap rarity</b> (Intave MicroBlink port): per-player inter-arrival histogram
 *   while actually moving; 150–1000ms gaps with ~impossible histogram probability near
 *   combat accumulate toward a flag. The histogram is the player's own baseline, so
 *   genuine laggers train theirs up instead of flagging.</li>
 *   <li><b>Combat correlation</b> (MicroBlink χ² port): 2×2 table of
 *   (lagging × victim-near) flags χ² above threshold — honest jitter does not
 *   synchronize with fights, DYNAMIC fakelag does by construction.</li>
 *   <li><b>Attack desync</b> (ours): fraction of combat ticks carrying attacks but no
 *   movement packet. Flushed-on-attack behavior leaves exactly this trace.</li>
 * </ol>
 *
 * <p>Mitigation: while correlation is confirmed, attacks arriving mid-gap are cancelled
 * (configurable), mirroring how {@code Reach} cancels impossible hits.</p>
 *
 * <p>Deviations from Intave: connection-jitter limit replaced by a self-calibrating
 * baseline (median recent gap + 100ms, 70ms floor) since Grim exposes no per-connection
 * feedback-delay series; histogram warmup (50 samples) and χ² minimum-total (20)
 * guards added; gaps above 1000ms ignored (idle/lag spikes, not fakelag windows).</p>
 */
@CheckData(
        name = "FakeLagIV",
        stableKey = "grim.combat.iv_fakelag",
        description = "Queued movement bursts around combat (fakelag)",
        decay = 0.05,
        setback = 25
)
public class FakeLagIV extends Check implements PacketReceiveListener {

    private static final long JOIN_GRACE_MS = 5000L;

    private final CombatState combat = new CombatState();
    private final Histogram histogram = new Histogram(0, 500, 10, 2400);
    private final Deque<Long> recentGaps = new ArrayDeque<>();
    private final long[][] table = new long[2][2];

    private final long creationTime = System.currentTimeMillis();
    private long lastMoveMs;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean hasLastPos;
    private boolean prevMoved;

    private double gapVl;
    private long lastChiFlagMs;
    private long lastMitFlagMs;

    private boolean tickMove;
    private boolean tickAttack;
    private int combatTicks;
    private int silentTicks;

    private boolean enabled = true;
    private boolean cancelHits = true;
    private double chi2Threshold = 15;
    private double desyncFraction = 0.5;

    public FakeLagIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.fakelag.enable", true);
        cancelHits = config.getBooleanElse("Intave.fakelag.cancel-hits", true);
        chi2Threshold = config.getDoubleElse("Intave.fakelag.chi2", 15);
        desyncFraction = config.getDoubleElse("Intave.fakelag.desync-fraction", 0.5);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        Integer attackId = attackEntityId(event);
        if (attackId != null) {
            combat.onAttack(attackId);
            tickAttack = true;

            // Mitigation: correlated lag + attack arriving mid-gap = burst hit.
            long now = System.currentTimeMillis();
            if (cancelHits && shouldModifyPackets()
                    && now - lastChiFlagMs < 5000 && now - lastMoveMs > limit()
                    && now - creationTime > JOIN_GRACE_MS) {
                event.setCancelled(true);
                player.onPacketCancel();
                if (now - lastMitFlagMs > 5000) {
                    lastMitFlagMs = now;
                    flag("mitigated lag-burst hit");
                }
            }
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;

        boolean teleport = player.packetStateData.lastPacketWasTeleport;
        if (!teleport) {
            foldTick();
            sampleMovement();
        }
        tickMove = false;
        tickAttack = false;
        if (teleport) {
            prevMoved = false;
        }
    }

    /** Per-tick attack/movement fold for the desync sub-detector. */
    private void foldTick() {
        if (!combat.recentlyAttacked(3000)) return;
        combatTicks++;
        if (tickAttack && !tickMove) silentTicks++;
        if (combatTicks >= 100) {
            double fraction = (double) silentTicks / combatTicks;
            if (fraction > desyncFraction) {
                flag("attacks detached from movement, " + silentTicks + "/" + combatTicks + " silent ticks");
            }
            combatTicks = 0;
            silentTicks = 0;
        }
    }

    /** Inter-arrival gap sampling + rarity/correlation sub-detectors. */
    private void sampleMovement() {
        long now = System.currentTimeMillis();
        double dx = player.x - lastX;
        double dy = player.y - lastY;
        double dz = player.z - lastZ;
        lastX = player.x;
        lastY = player.y;
        lastZ = player.z;

        boolean moved = hasLastPos && dx * dx + dy * dy + dz * dz > 0.125 * 0.125;
        hasLastPos = true;
        tickMove = true;

        if (moved && prevMoved && lastMoveMs != 0) {
            long gap = now - lastMoveMs;
            histogram.add(Math.min(gap, 500));
            recentGaps.addLast(gap);
            while (recentGaps.size() > 40) recentGaps.removeFirst();

            boolean lagging = gap > limit();
            boolean near = victimNear();
            table[lagging ? 0 : 1][near ? 0 : 1]++;

            if (lagging && combat.recentlyAttacked(1000) && tableTotal() >= 20
                    && chi2() > chi2Threshold
                    && now - creationTime > JOIN_GRACE_MS) {
                lastChiFlagMs = now;
                flag("micro-lagging entity-aligned, chi2=" + String.format("%.1f", chi2()));
            }

            if (gap >= 150 && gap <= 1000 && histogram.size() >= 50
                    && histogram.normalProbability(gap) < 0.000001
                    && combat.recentlyAttacked(1250)
                    && now - creationTime > JOIN_GRACE_MS) {
                if (++gapVl > 5) {
                    flag("micro-lagging in combat, " + String.format("%.6f", histogram.normalProbability(gap) * 100)
                            + "% likelihood of " + gap + "ms");
                    gapVl = 0;
                }
            } else if (gapVl > 0) {
                gapVl = Math.max(0, gapVl - 0.007);
            }
        }
        prevMoved = moved;
        lastMoveMs = now;
    }

    private boolean victimNear() {
        if (combat.lastTargetId() < 0) return false;
        PerfectAim aim = PerfectAim.compute(player, combat.lastTargetId(), player.yaw, player.pitch);
        return aim.valid && aim.reach <= 4.0;
    }

    /** Self-calibrating lag limit: median recent gap + 100ms, 70ms floor. */
    private double limit() {
        if (recentGaps.isEmpty()) return 150;
        List<Long> sorted = new ArrayList<>(recentGaps);
        sorted.sort(Long::compare);
        double median = sorted.get(sorted.size() / 2);
        return Math.max(70, median + 100);
    }

    private long tableTotal() {
        return table[0][0] + table[0][1] + table[1][0] + table[1][1];
    }

    private double chi2() {
        long total = tableTotal();
        if (total == 0) return 0;
        long[] rowSum = {table[0][0] + table[0][1], table[1][0] + table[1][1]};
        long[] colSum = {table[0][0] + table[1][0], table[0][1] + table[1][1]};
        double chi2 = 0;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                double expected = (double) rowSum[i] * colSum[j] / total;
                if (expected <= 0) continue;
                double diff = table[i][j] - expected;
                chi2 += diff * diff / expected;
            }
        }
        return chi2;
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
