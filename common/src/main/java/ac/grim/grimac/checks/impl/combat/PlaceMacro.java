package ac.grim.grimac.checks.impl.combat;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Detects scripted place/use macros: runs of block-place or use-item actions each
 * landing within one tick of a hotbar slot change, cycling through distinct slots —
 * the cartcore pattern (rail → cart → flint swaps with right-clicks on consecutive
 * exact ticks, slab subroutine, auto-web sequencing).
 *
 * <p>Tripwire: 4+ swapped-uses inside a 10-tick window spanning 3+ distinct slots,
 * confirmed twice. Plain two-slot toggling (blocks/pickaxe, bridging, eat-then-place)
 * is everyday legit play and explicitly excluded; scripted place macros cycle three
 * or more slots with machine spacing.</p>
 */
@CheckData(
        name = "PlaceMacro",
        stableKey = "grim.combat.place_macro",
        description = "Tick-exact slot-to-place macro cadence",
        decay = 0.05,
        setback = 25
)
public class PlaceMacro extends Check implements PacketReceiveListener {

    private static final int WINDOW_TICKS = 10;
    private static final int USES_THRESHOLD = 4;
    private static final int SLOTS_THRESHOLD = 3;

    private static final class SwappedUse {
        final int tick;
        final int slot;

        SwappedUse(int tick, int slot) {
            this.tick = tick;
            this.slot = slot;
        }
    }

    private final Deque<SwappedUse> window = new ArrayDeque<>();
    private int tickCounter;
    private int lastSlotTick = -1000;
    private int confirmations;
    private long firstConfirmMs;
    private boolean enabled = true;

    public PlaceMacro(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("PlaceMacro.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            lastSlotTick = tickCounter;
            return;
        }

        if (isTickPacket(event.getPacketType())) {
            tickCounter++;
            while (!window.isEmpty() && tickCounter - window.peekFirst().tick > WINDOW_TICKS) {
                window.removeFirst();
            }
            return;
        }

        if (!isUse(event)) return;
        if (player.packetStateData.lastPacketWasTeleport) return;

        // Use within one tick of a slot change = one macro step.
        if (tickCounter - lastSlotTick > 1) return;

        int slot = player.packetStateData.lastSlotSelected;
        window.addLast(new SwappedUse(tickCounter, slot));

        if (window.size() >= USES_THRESHOLD) {
            Set<Integer> slots = new HashSet<>();
            for (SwappedUse use : window) slots.add(use.slot);
            // Two-slot toggling (blocks/pickaxe, torch-bridging, eat-then-place)
            // is everyday legit play — macros cycle three or more slots.
            if (slots.size() >= SLOTS_THRESHOLD) {
                long now = System.currentTimeMillis();
                if (confirmations == 0 || now - firstConfirmMs > 60_000L) {
                    confirmations = 1;
                    firstConfirmMs = now;
                } else if (++confirmations >= 2) {
                    flag("place macro cadence, " + window.size() + " swapped uses over "
                            + slots.size() + " slots");
                    confirmations = 0;
                    window.clear();
                }
            }
        }
    }

    private static boolean isUse(PacketReceiveEvent event) {
        return event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                || event.getPacketType() == PacketType.Play.Client.USE_ITEM;
    }
}
