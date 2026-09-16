package ac.grim.grimac.checks.impl.aim.mx;

import ac.grim.grimac.GrimAPI;
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
import kireiko.dev.millennium.ml.ClientML;
import kireiko.dev.millennium.ml.FactoryML;
import kireiko.dev.millennium.ml.data.ObjectML;
import kireiko.dev.millennium.ml.data.ResultML;
import kireiko.dev.millennium.ml.data.module.FlagType;
import kireiko.dev.millennium.ml.data.module.ModuleML;
import kireiko.dev.millennium.ml.data.module.ModuleResultML;
import kireiko.dev.millennium.ml.logic.Millennium;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Grim-native port of MX-Project's {@code AimMLCheck} (Unlicense, Kireiko).
 *
 * <p>Buffers rotation deltas during combat and runs them through MX's 8 trained models
 * (7 legacy statistical tables + 1 Bi-LSTM RNN, weights bundled under {@code /ml/*.dat})
 * on a background thread. This is the check that catches humanized/temporal auras whose
 * per-packet statistics look clean but whose aim <i>rhythm</i> over hundreds of rotations
 * matches learned cheat distributions.</p>
 *
 * <p>Faithful to upstream: 600-delta window for legacy models, 150-delta window for the
 * RNN, 3s post-attack gating, max-severity aggregation across models. Deviations:
 * ProtocolLib events replaced by PacketEvents + Grim's compensated {@link RotationUpdate};
 * punish/VL replaced by Grim {@code flag()}/{@code reward()}; dataset recording and
 * on-server training are not ported (inference only); models load once globally from
 * bundled resources and a missing model is skipped rather than replaced with random
 * weights.</p>
 */
@CheckData(
        name = "AimMLMX",
        stableKey = "grim.aim.mx_ml",
        description = "MX machine-learning aimbot detection (statistical models + RNN)",
        decay = 0.02,
        setback = 30
)
public class AimMLMX extends Check implements RotationListener, PacketReceiveListener {

    private static final int LEGACY_WINDOW = 600;
    private static final int RNN_WINDOW = 150;
    private static final int LEGACY_MODEL_COUNT = 7;
    private static final int RNN_MODEL_INDEX = 7;
    private static final long ATTACK_WINDOW_MS = 3000L;

    /** Models are global (shared across players), exactly like MX's static FactoryML cache. */
    private static final AtomicBoolean MODELS_LOADED = new AtomicBoolean(false);

    private final List<Float> yawDeltas = new ArrayList<>(LEGACY_WINDOW + 4);
    private final List<Float> pitchDeltas = new ArrayList<>(LEGACY_WINDOW + 4);

    private long lastAttackMillis = 0L;
    // Local kill-switch. Do NOT use Check.setEnabled here: PunishmentManager owns
    // that flag (Combat punish group matches "Aim", which covers this check).
    private boolean mxEnabled = true;

    public AimMLMX(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        mxEnabled = config.getBooleanElse("MX.aim-ml.enable", true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            lastAttackMillis = System.currentTimeMillis();
        } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                lastAttackMillis = System.currentTimeMillis();
            }
        }
    }

    @Override
    public void process(final RotationUpdate rotationUpdate) {
        if (!mxEnabled) return;
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
        if (dx == 0 && dy == 0) return;

        ensureModelsLoaded();

        yawDeltas.add(dx);
        pitchDeltas.add(dy);

        // MX feeds both windows from the same delta stream: the RNN list is cleared
        // every 150 deltas while the legacy list grows to 600, so at sizes
        // 150/300/450/600 the RNN sees exactly the last 150 deltas — reproduced here
        // with tail slices, including firing alongside legacy at 600 like upstream.
        if (yawDeltas.size() % RNN_WINDOW == 0) {
            int from = yawDeltas.size() - RNN_WINDOW;
            dispatchRNN(new ArrayList<>(yawDeltas.subList(from, yawDeltas.size())),
                    new ArrayList<>(pitchDeltas.subList(from, pitchDeltas.size())));
        }
        if (yawDeltas.size() >= LEGACY_WINDOW) {
            List<Float> yawSnap = new ArrayList<>(yawDeltas);
            List<Float> pitchSnap = new ArrayList<>(pitchDeltas);
            yawDeltas.clear();
            pitchDeltas.clear();
            dispatchLegacy(yawSnap, pitchSnap);
        }
    }

    private static void ensureModelsLoaded() {
        if (MODELS_LOADED.compareAndSet(false, true)) {
            try {
                ClientML.run();
            } catch (Exception e) {
                kireiko.dev.millennium.ml.logic.Logger.error("MX model loading failed: " + e);
            }
        }
    }

    private void dispatchRNN(List<Float> yawSnap, List<Float> pitchSnap) {
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            Millennium model = FactoryML.getModel(RNN_MODEL_INDEX);
            if (model == null || RNN_MODEL_INDEX >= ClientML.MODEL_LIST.size()) return;
            try {
                ResultML result = model.checkData(toStack(yawSnap, pitchSnap));
                ModuleML module = ClientML.MODEL_LIST.get(RNN_MODEL_INDEX);
                ModuleResultML moduleResult = module.getResult(result);
                handleResult(moduleResult, Set.of(module.getName()));
            } catch (Exception e) {
                kireiko.dev.millennium.ml.logic.Logger.error("AimMLMX RNN inference failed: " + e);
            }
        });
    }

    private void dispatchLegacy(List<Float> yawSnap, List<Float> pitchSnap) {
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            try {
                List<ObjectML> stack = toStack(yawSnap, pitchSnap);
                ModuleResultML finalResult = new ModuleResultML(0, FlagType.NORMAL, null);
                Set<String> modelsThatFlagged = new HashSet<>();

                for (int i = 0; i < LEGACY_MODEL_COUNT && i < ClientML.MODEL_LIST.size(); i++) {
                    Millennium model = FactoryML.getModel(i);
                    if (model == null) continue;
                    ResultML result = model.checkData(stack);
                    ModuleML module = ClientML.MODEL_LIST.get(i);
                    ModuleResultML moduleResult = module.getResult(result);

                    if (moduleResult.getType() != FlagType.NORMAL) {
                        modelsThatFlagged.add(module.getName());
                    }
                    if (finalResult.getInfo() == null) {
                        finalResult = moduleResult;
                    } else {
                        int finalLevel = finalResult.getType().getLevel();
                        int tempLevel = moduleResult.getType().getLevel();
                        if (finalLevel < tempLevel
                                || (finalLevel == tempLevel && finalResult.getPriority() < moduleResult.getPriority())) {
                            finalResult = moduleResult;
                        }
                    }
                }
                handleResult(finalResult, modelsThatFlagged);
            } catch (Exception e) {
                kireiko.dev.millennium.ml.logic.Logger.error("AimMLMX legacy inference failed: " + e);
            }
        });
    }

    private static List<ObjectML> toStack(List<Float> yawSnap, List<Float> pitchSnap) {
        ObjectML yaw = new ObjectML(new ArrayList<>());
        ObjectML pitch = new ObjectML(new ArrayList<>());
        for (Float f : yawSnap) yaw.getValues().add((double) f);
        for (Float f : pitchSnap) pitch.getValues().add((double) f);
        List<ObjectML> stack = new ArrayList<>(2);
        stack.add(yaw);
        stack.add(pitch);
        return stack;
    }

    /**
     * MX tiers (unusual/strange/suspected with VL 1/2/4) collapse onto Grim's single-flag
     * pipeline; the tier and triggering models ride in the verbose so punish groups and
     * analysts keep the signal. Clean windows cool the check down via {@code reward()}.
     */
    private void handleResult(ModuleResultML result, Set<String> modelsThatFlagged) {
        if (result == null || result.getType() == FlagType.NORMAL) {
            reward();
            return;
        }
        String info = result.getInfo() != null ? " " + result.getInfo() : "";
        flag("ML " + result.getType() + " " + modelsThatFlagged + info);
    }
}
