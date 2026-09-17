package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Combat auto-swap macro detection (BreachSwap / ShieldBreaker / stun-slam pattern).
 *
 * <p>These macros swap weapons around attacks, but each with a different rhythm — so
 * this check runs three sub-detectors off one shared slot/attack tracker, and never
 * flags raw speed (a skilled player can sandwich inside one tick):</p>
 * <ol>
 *   <li><b>Sandwich regularity</b> (BreachSwap): slot A → B → attack → A collected
 *   generously, judged strictly — the last ~10 sandwich durations must average under
 *   150ms with under 30ms spread <i>and</i> cover 70%+ of attacks. Metronome, not fast.</li>
 *   <li><b>Bracket runs</b> (ShieldBreaker): a swap pair bracketing 5+ attacks at
 *   ~50ms spacing with no mid swaps. Single-swap engagements, machine cadence.</li>
 *   <li><b>Snap swaps</b> (stun slam): axe-swap → shield-breaking attack → mace-swap
 *   compressed inside a <i>single tick</i>. Each tick holding 2+ swaps and 1+
 *   attack is one triple-tick event; 5+ inside a rolling 60s window flags. A
 *   cross-tick lockstep test (8+ of 10 attacks followed by a swap within a tick,
 *   7+ of 10 swaps attack-explained) backstops lag-split variants. No player
 *   triple-inputs inside 50ms repeatedly; scroll-spammers fail both tests.</li>
 * </ol>
 *
 * <p>Alert-only: swapping is core PvP movement and cancellation would break legit fast
 * play. Version-agnostic (packet types only, wall-clock timing).</p>
 */
@CheckData(
        name = "SwapMacro",
        stableKey = "grim.combat.swap_macro",
        description = "Robotic weapon-swap attack automation",
        decay = 0.05,
        setback = 25
)
public class SwapMacro extends Check implements PacketReceiveListener {

    // Shared tracking
    private int prevSlot = -1;
    private int tickCounter;
    private boolean enabled = true;

    // A. sandwich state
    private int sandwichA = -1;
    private long sandwichSwapMs;
    private boolean sandwichAttacked;
    private final Deque<Sandwich> sandwiches = new ArrayDeque<>();
    private final Deque<Long> attackTimes = new ArrayDeque<>();

    // B. bracket state
    private boolean bracketOpen;
    private final Deque<Long> bracketAttacks = new ArrayDeque<>();
    private long bracketOpenedMs;

    // D. snap-swap state (stun slam): attack ticks awaiting their follow-up swap.
    private final Deque<Integer> pendingAttacks = new ArrayDeque<>();
    private final Deque<Boolean> snapOutcomes = new ArrayDeque<>();
    // Whether each recent swap was explained by a preceding attack (lockstep test).
    private final Deque<Boolean> swapExplained = new ArrayDeque<>();
    // Same-tick triple events: ticks holding 2+ swaps and 1+ attack.
    private final Deque<Long> tripleTimes = new ArrayDeque<>();
    private int curTickSwaps;
    private int curTickAttacks;

    private static final class Sandwich {
        final long time;
        final long durationMs;

        Sandwich(long time, long durationMs) {
            this.time = time;
            this.durationMs = durationMs;
        }
    }

    public SwapMacro(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("SwapMacro.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (isTickPacket(event.getPacketType())) {
            tickCounter++;
            if (!player.packetStateData.lastPacketWasTeleport) {
                finishTick();
            } else {
                curTickSwaps = 0;
                curTickAttacks = 0;
            }
            sweepPendingAttacks();
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            onSlotChange(new WrapperPlayClientHeldItemChange(event).getSlot());
            return;
        }

        if (attackEntityId(event) != null) {
            onAttack();
        }
    }

    // ---- shared slot/attack plumbing ----

    private void onSlotChange(int slot) {
        long now = System.currentTimeMillis();
        curTickSwaps++;

        // D. resolve pending attacks: swapped within one tick after the attack.
        // Whether THIS swap was explained by an attack feeds the lockstep test.
        boolean explained = false;
        boolean resolvedAny = false;
        while (!pendingAttacks.isEmpty() && tickCounter - pendingAttacks.peekFirst() > 1) {
            snapOutcomes.addLast(false);
            pendingAttacks.removeFirst();
            resolvedAny = true;
        }
        while (!pendingAttacks.isEmpty() && tickCounter - pendingAttacks.peekFirst() <= 1
                && tickCounter - pendingAttacks.peekFirst() >= 0) {
            snapOutcomes.addLast(true);
            pendingAttacks.removeFirst();
            resolvedAny = true;
            explained = true;
        }
        while (snapOutcomes.size() > 10) snapOutcomes.removeFirst();
        swapExplained.addLast(explained);
        while (swapExplained.size() > 30) swapExplained.removeFirst();
        if (resolvedAny) evaluateSnapSwaps();

        // A. sandwich tracking
        if (sandwichA == -1) {
            if (prevSlot != -1 && prevSlot != slot) {
                sandwichA = prevSlot;
                sandwichSwapMs = now;
                sandwichAttacked = false;
            }
        } else if (slot == sandwichA) {
            if (sandwichAttacked) {
                long duration = now - sandwichSwapMs;
                if (duration <= 2000) {
                    sandwiches.addLast(new Sandwich(now, duration));
                    while (sandwiches.size() > 10) sandwiches.removeFirst();
                    evaluateSandwiches();
                }
            }
            sandwichA = -1;
        } else {
            // Swapped elsewhere mid-sandwich: abandon it, open a fresh one.
            sandwichA = prevSlot;
            sandwichSwapMs = now;
            sandwichAttacked = false;
        }

        // B. bracket tracking: any swap closes the open bracket, opens a fresh one.
        if (bracketOpen) closeBracket(now);
        bracketOpen = true;
        bracketAttacks.clear();
        bracketOpenedMs = now;

        prevSlot = slot;
    }

    private void onAttack() {
        long now = System.currentTimeMillis();
        curTickAttacks++;
        attackTimes.addLast(now);
        while (!attackTimes.isEmpty() && now - attackTimes.peekFirst() > 60_000L) {
            attackTimes.removeFirst();
        }
        pendingAttacks.addLast(tickCounter);
        while (pendingAttacks.size() > 50) {
            snapOutcomes.addLast(false);
            pendingAttacks.removeFirst();
        }
        while (snapOutcomes.size() > 10) snapOutcomes.removeFirst();

        // A. mark sandwich attacked (generous 500ms window; strictness comes
        // from the statistics, not this gate).
        if (sandwichA != -1 && now - sandwichSwapMs <= 500) {
            sandwichAttacked = true;
        }

        // B. record bracket attack (cap the run so one endless bracket
        // cannot grow unbounded; the capped run still evaluates on close).
        if (bracketOpen) {
            bracketAttacks.addLast(now);
            if (bracketAttacks.size() >= 200) {
                closeBracket(now);
                bracketOpen = true;
                bracketOpenedMs = now;
            }
        }
    }

    // ---- A. sandwich regularity (BreachSwap) ----

    private void evaluateSandwiches() {
        if (sandwiches.size() < 6) return;
        double sum = 0;
        for (Sandwich s : sandwiches) sum += s.durationMs;
        double mean = sum / sandwiches.size();
        double var = 0;
        for (Sandwich s : sandwiches) var += (s.durationMs - mean) * (s.durationMs - mean);
        double stddev = Math.sqrt(var / sandwiches.size());

        long now = System.currentTimeMillis();
        int recentAttacks = 0;
        for (long t : attackTimes) {
            if (now - t <= 60_000L) recentAttacks++;
        }
        double rate = recentAttacks == 0 ? 0 : (double) sandwiches.size() / recentAttacks;

        if (mean < 150 && stddev < 30 && rate > 0.7) {
            flag("metronome swap sandwiches, mean=" + String.format("%.0f", mean)
                    + "ms stddev=" + String.format("%.0f", stddev)
                    + "ms rate=" + String.format("%.2f", rate));
            sandwiches.clear();
        }
    }

    // ---- B. bracket runs (ShieldBreaker) ----

    private void closeBracket(long now) {
        bracketOpen = false;
        if (bracketAttacks.size() >= 5 && now - bracketOpenedMs <= 30_000L) {
            long first = bracketAttacks.peekFirst();
            long last = bracketAttacks.peekLast();
            double avgSpacing = (double) (last - first) / (bracketAttacks.size() - 1);
            if (avgSpacing >= 30 && avgSpacing <= 80) {
                flag("swap-bracketed attack run, " + bracketAttacks.size()
                        + " attacks @" + String.format("%.0f", avgSpacing) + "ms");
            }
        }
        bracketAttacks.clear();
    }

    // ---- D. snap swaps (stun slam) ----

    /** Close out the finished tick: same-tick triple detection. */
    private void finishTick() {
        long now = System.currentTimeMillis();
        if (curTickSwaps >= 2 && curTickAttacks >= 1) {
            tripleTimes.addLast(now);
        }
        curTickSwaps = 0;
        curTickAttacks = 0;
        while (!tripleTimes.isEmpty() && now - tripleTimes.peekFirst() > 60_000L) {
            tripleTimes.removeFirst();
        }
        if (tripleTimes.size() >= 5) {
            flag("same-tick swap triple, " + tripleTimes.size() + " in 60s");
            tripleTimes.clear();
        }
    }

    /** Age out attacks that never got a follow-up swap. */
    private void sweepPendingAttacks() {
        boolean resolvedAny = false;
        while (!pendingAttacks.isEmpty() && tickCounter - pendingAttacks.peekFirst() > 1) {
            snapOutcomes.addLast(false);
            pendingAttacks.removeFirst();
            resolvedAny = true;
        }
        while (snapOutcomes.size() > 10) snapOutcomes.removeFirst();
        if (resolvedAny) evaluateSnapSwaps();
    }

    private void evaluateSnapSwaps() {
        if (snapOutcomes.size() < 10 || swapExplained.size() < 10) return;
        int snaps = 0;
        for (boolean b : snapOutcomes) if (b) snaps++;
        // Lockstep test over the last 10 swaps: macro swaps are ~all explained by
        // a preceding attack; scroll-spam swaps mostly are not.
        int explained = 0;
        int seen = 0;
        java.util.Iterator<Boolean> it = swapExplained.descendingIterator();
        while (it.hasNext() && seen < 10) {
            seen++;
            if (it.next()) explained++;
        }
        if (snaps >= 8 && explained >= 7) {
            flag("post-attack snap swaps, " + snaps + "/10 within 1 tick, "
                    + explained + "/10 swaps attack-explained");
            snapOutcomes.clear();
        }
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
