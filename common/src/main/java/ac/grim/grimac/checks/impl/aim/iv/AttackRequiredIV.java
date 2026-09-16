package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

/**
 * Grim-native port of Intave's {@code AttackRequiredHeuristic}
 * (source-available, intave/intave).
 *
 * <p>Detects autoclicker/killaura packet suppression: an arm swing with the crosshair on
 * the victim but no accompanying attack packet. Per tick it latches swing vs attack
 * presence; a swing onto the target (expanded raytrace, survival/creative reach) with
 * no attack needs a repeat within the throttle to flag. Clean attack ticks decay.</p>
 *
 * <p>Restricted to 1.8 clients like upstream — on 1.9+ the attack cooldown legitimately
 * produces swings without attacks. Fishing-rod casts are exempt (right-click swing).</p>
 */
@CheckData(
        name = "AttackRequiredIV",
        stableKey = "grim.combat.iv_attack_required",
        description = "Swung at victim but suppressed the attack packet (Intave port)",
        decay = 0.05,
        setback = 25
)
public class AttackRequiredIV extends Check implements PacketReceiveListener {

    private final CombatState combat = new CombatState();
    private boolean swung;
    private boolean attacked;
    private int vl;
    private long lastFlagMs;
    private boolean enabled = true;

    public AttackRequiredIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.attack-required.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;
        if (!player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8)
                || !player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            swung = true;
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            attacked = true;
            combat.onAttack(new WrapperPlayClientAttack(event).getEntityId());
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                attacked = true;
                combat.onAttack(packet.getEntityId());
            }
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;
        if (player.packetStateData.lastPacketWasTeleport) {
            swung = false;
            attacked = false;
            return;
        }

        try {
            if (swung && !attacked && combat.lastTargetId() >= 0 && !holdingRod()) {
                double limit = player.gamemode == GameMode.CREATIVE ? 5.0 : 3.0;
                PerfectAim aim = PerfectAim.computeExpanded(player, combat.lastTargetId(),
                        player.yaw, player.pitch, 0.05);
                if (aim.valid && aim.inSight && aim.reach <= limit) {
                    if (++vl >= 2 && System.currentTimeMillis() - lastFlagMs > 1500) {
                        flag("missed attack packet");
                        lastFlagMs = System.currentTimeMillis();
                        vl = 0;
                    }
                }
            } else if (swung && attacked && vl > 0) {
                vl--;
            }
        } finally {
            swung = false;
            attacked = false;
        }
    }

    private boolean holdingRod() {
        try {
            return player.inventory.getItemInHand(InteractionHand.MAIN_HAND).getType() == ItemTypes.FISHING_ROD;
        } catch (Exception e) {
            return false;
        }
    }
}
