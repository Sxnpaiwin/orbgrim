package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code ToolSwitchHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects AutoTool abuse that swaps to the best tool mid-mine and swaps back within a
 * tick: a hotbar switch landing within 1 tick of block-break START arms suspicion, and a
 * return to the original slot within 1 tick of STOP confirms the macro ({@code vl > 3}
 * buffer). Break timing alone cannot see this — only the slot sequence can.</p>
 */
@CheckData(
        name = "ToolSwitchIV",
        stableKey = "grim.world.iv_tool_switch",
        description = "Tool swap mid-mine and back within a tick (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ToolSwitchIV extends Check implements PacketReceiveListener {

    private int ticksSinceLastBreak = 1000;
    private int ticksSinceLastStop = 1000;
    private int lastSlot;
    private boolean suspiciousBreakStart;
    private int vl;
    private boolean enabled = true;

    public ToolSwitchIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.tool-switch.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (isTickPacket(event.getPacketType())) {
            ticksSinceLastBreak++;
            ticksSinceLastStop++;
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.START_DIGGING) {
                ticksSinceLastBreak = 0;
            } else if (action == DiggingAction.CANCELLED_DIGGING) {
                ticksSinceLastStop = 0;
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
            int currentSlot = player.packetStateData.lastSlotSelected;

            if (ticksSinceLastBreak <= 1) {
                suspiciousBreakStart = true;
                lastSlot = currentSlot;
            }

            if (suspiciousBreakStart && ticksSinceLastStop <= 1 && lastSlot == slot) {
                suspiciousBreakStart = false;
                if (++vl > 3) {
                    flag("sent suspicious slot packets while breaking blocks, "
                            + ticksSinceLastStop + " ticks since stopping");
                    vl = 0;
                }
            }
        }
    }
}
