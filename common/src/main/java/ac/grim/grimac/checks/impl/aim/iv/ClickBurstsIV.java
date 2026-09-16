package ac.grim.grimac.checks.impl.aim.iv;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Grim-native port of Intave's {@code Bursts} click-pattern check
 * (source-available, intave/intave).
 *
 * <p>Tick-burst clickers: instead of millisecond timing it folds every tick into one
 * action (attack > click > place priority) over a 40-tick window and scores sustained
 * same-action runs longer than 5 ticks. Runs containing a multi-action tick
 * (double-click) reduce the score; any block place or active block-breaking in the
 * window cancels — bridging and mining never qualify. Catches hold-to-click auras with
 * inhuman duty cycles that millisecond statistics miss.</p>
 */
@CheckData(
        name = "ClickBurstsIV",
        stableKey = "grim.combat.iv_click_bursts",
        description = "Sustained tick-burst clicking (Intave port)",
        decay = 0.05,
        setback = 25
)
public class ClickBurstsIV extends Check implements PacketReceiveListener {

    private static final int WINDOW_TICKS = 40;

    private final List<Character> history = new ArrayList<>();
    private final List<Integer> intensities = new ArrayList<>();
    private int swings;
    private int attacks;
    private int places;
    private boolean breaking;
    private boolean windowBreaking;
    private boolean windowPlace;
    private boolean windowDouble;
    private boolean enabled = true;

    public ClickBurstsIV(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("Intave.click-bursts.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            swings++;
            return;
        }
        if (isAttack(event)) {
            attacks++;
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            places++;
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.START_DIGGING) breaking = true;
            else if (action == DiggingAction.CANCELLED_DIGGING
                    || action == DiggingAction.FINISHED_DIGGING) breaking = false;
            return;
        }

        if (!isTickPacket(event.getPacketType())) return;

        char tick;
        if (attacks > 0) tick = 'A';
        else if (swings > 0) tick = 'C';
        else if (places > 0) tick = 'P';
        else tick = '.';
        history.add(tick);
        intensities.add(swings + attacks + places);
        if (breaking) windowBreaking = true;
        if (places > 0) windowPlace = true;
        if ((tick == 'A' || tick == 'C') && swings + attacks + places > 1) windowDouble = true;
        swings = 0;
        attacks = 0;
        places = 0;

        if (history.size() >= WINDOW_TICKS) {
            analyze();
            history.clear();
            intensities.clear();
            windowBreaking = false;
            windowPlace = false;
            windowDouble = false;
        }
    }

    private void analyze() {
        int vl = 0;
        StringBuilder pattern = new StringBuilder();
        int run = 0;
        boolean runDouble = false;
        for (int i = 0; i < history.size(); i++) {
            char c = history.get(i);
            pattern.append(c);
            if (c == 'A' || c == 'C') {
                run++;
                if (intensities.get(i) > 1) runDouble = true;
            } else {
                if (run > 5) vl += run + (runDouble ? -1 : 2);
                run = 0;
                runDouble = false;
            }
        }
        if (run > 5) vl += run + (runDouble ? -1 : 2);

        boolean cancel = windowBreaking || windowPlace;
        if (!cancel && vl >= 20) {
            flag("exhibits click bursts" + (windowDouble ? "" : " without double clicks")
                    + " [" + pattern + "]");
        }
    }

    private static boolean isAttack(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) return true;
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            return new WrapperPlayClientInteractEntity(event).getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        }
        return false;
    }
}
