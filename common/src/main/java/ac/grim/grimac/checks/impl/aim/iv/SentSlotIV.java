package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code SentSlotTwice} protocol-scanner part
 * (source-available, intave/intave).
 *
 * <p>Detects slot-spoof keepalives (Autoblock/Scaffold crutches) that redundantly resend
 * the already-held hotbar slot. Vanilla never resends the current slot, so after a
 * 4-packet warmup this is a near-zero-FP signal. Disabled by Intave convention when its
 * VL is configured to 0.</p>
 */
@CheckData(
        name = "SentSlotTwiceIV",
        stableKey = "grim.badpackets.iv_sent_slot_twice",
        description = "Resent already-held hotbar slot (Intave port)",
        decay = 0.05,
        setback = 25
)
public class SentSlotIV extends Check implements PacketReceiveListener {

    private int lastSlot = -1;
    private int slotPacketsSent;
    private double vlThreshold = 100;
    private boolean enabled = true;

    public SentSlotIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        vlThreshold = config.getDoubleElse("Intave.sent-slot-twice.vl", 100);
        enabled = config.getBooleanElse("Intave.sent-slot-twice.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || vlThreshold == 0) return;
        if (event.getPacketType() != PacketType.Play.Client.HELD_ITEM_CHANGE) return;

        int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
        // Upstream only scores after a 4-packet warmup so login resyncs never count.
        if (lastSlot == slot && slot > 0 && slotPacketsSent > 4) {
            flag("sent slot twice, slot " + slot);
        }
        lastSlot = slot;
        slotPacketsSent++;
    }
}
