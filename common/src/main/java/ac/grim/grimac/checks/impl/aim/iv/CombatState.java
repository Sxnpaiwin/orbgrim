package ac.grim.grimac.checks.impl.aim.iv;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shared per-check combat state for the Intave-ported checks: attack recency, target
 * identity/switch tracking, and victim-movement estimation.
 *
 * <p>Mirrors the slices of Intave's {@code AttackMetadata} the ports need
 * ({@code recentlyAttacked}, {@code recentlySwitchedEntity}, {@code moving}) without
 * depending on Intave's tracker. Each check owns one instance (no cross-check wiring).</p>
 */
public final class CombatState {

    private long lastAttackMs = 0L;
    private int lastTargetId = -1;
    private long lastSwitchMs = 0L;

    private final Deque<Sample> victimWindow = new ArrayDeque<>();

    private static final class Sample {
        final long time;
        final double x;
        final double y;
        final double z;

        Sample(long time, double x, double y, double z) {
            this.time = time;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public void onAttack(int entityId) {
        long now = System.currentTimeMillis();
        lastAttackMs = now;
        if (entityId != lastTargetId) {
            lastTargetId = entityId;
            lastSwitchMs = now;
        }
    }

    public boolean recentlyAttacked(long windowMs) {
        return System.currentTimeMillis() - lastAttackMs <= windowMs;
    }

    public boolean recentlySwitched(long windowMs) {
        return System.currentTimeMillis() - lastSwitchMs <= windowMs;
    }

    public int lastTargetId() {
        return lastTargetId;
    }

    /** Feed the victim's current hitbox center (e.g. from {@link PerfectAim}). */
    public void pushVictimCenter(double x, double y, double z) {
        long now = System.currentTimeMillis();
        victimWindow.addLast(new Sample(now, x, y, z));
        while (victimWindow.size() > 40) victimWindow.removeFirst();
        while (!victimWindow.isEmpty() && now - victimWindow.peekFirst().time > 1000) {
            victimWindow.removeFirst();
        }
    }

    /** Victim moved more than {@code threshold} blocks within the last second. */
    public boolean victimMoving(double threshold) {
        if (victimWindow.size() < 2) return false;
        Sample first = victimWindow.peekFirst();
        Sample last = victimWindow.peekLast();
        if (first == null || last == null) return false;
        double dx = last.x - first.x;
        double dy = last.y - first.y;
        double dz = last.z - first.z;
        return dx * dx + dy * dy + dz * dz > threshold * threshold;
    }
}
