package ac.grim.grimac.manager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Olympia debug feed: lightweight in-memory ring of recent flag events powering the
 * {@code /olympia} log GUI and per-player debug exports.
 *
 * <p>Deliberately independent of the datastore-backed {@code /grim history} — this works
 * even with storage disabled and keeps verboses in memory exactly as flagged. Bounded
 * (500 global / 100 per player); all methods are synchronized because flags arrive on
 * netty threads while the GUI reads on the main thread.</p>
 */
public final class FlagFeed {

    public static final FlagFeed INSTANCE = new FlagFeed();

    private static final int GLOBAL_CAP = 500;
    private static final int PLAYER_CAP = 100;

    public record Entry(long time, UUID uuid, String name, String check, String verbose, int vl) {}

    public record Offender(UUID uuid, String name, int flags) {}

    private final Deque<Entry> global = new ArrayDeque<>();
    private final Map<UUID, Deque<Entry>> byPlayer = new ConcurrentHashMap<>();

    private FlagFeed() {}

    public synchronized void record(UUID uuid, String name, String check, String verbose, int vl) {
        Entry entry = new Entry(System.currentTimeMillis(), uuid, name, check,
                verbose == null ? "" : verbose, vl);
        global.addLast(entry);
        while (global.size() > GLOBAL_CAP) global.removeFirst();
        Deque<Entry> personal = byPlayer.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        personal.addLast(entry);
        while (personal.size() > PLAYER_CAP) personal.removeFirst();
    }

    public synchronized List<Entry> recentGlobal(int limit) {
        return tail(global, limit);
    }

    public synchronized List<Entry> recentFor(UUID uuid, int limit) {
        Deque<Entry> personal = byPlayer.get(uuid);
        if (personal == null) return Collections.emptyList();
        return tail(personal, limit);
    }

    /** Offenders sorted by flag count, most recent name retained. */
    public synchronized List<Offender> topOffenders(int limit) {
        Map<UUID, Offender> aggregate = new LinkedHashMap<>();
        for (Entry entry : global) {
            Offender existing = aggregate.get(entry.uuid());
            if (existing == null) {
                aggregate.put(entry.uuid(), new Offender(entry.uuid(), entry.name(), 1));
            } else {
                aggregate.put(entry.uuid(),
                        new Offender(entry.uuid(), entry.name(), existing.flags() + 1));
            }
        }
        List<Offender> sorted = new ArrayList<>(aggregate.values());
        sorted.sort((a, b) -> Integer.compare(b.flags(), a.flags()));
        if (sorted.size() > limit) return sorted.subList(0, limit);
        return sorted;
    }

    private static List<Entry> tail(Deque<Entry> deque, int limit) {
        List<Entry> out = new ArrayList<>(Math.min(limit, deque.size()));
        int skip = Math.max(0, deque.size() - limit);
        int i = 0;
        for (Entry entry : deque) {
            if (i++ < skip) continue;
            out.add(entry);
        }
        return out;
    }
}
