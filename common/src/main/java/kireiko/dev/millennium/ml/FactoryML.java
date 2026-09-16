package kireiko.dev.millennium.ml;

import kireiko.dev.millennium.ml.logic.*;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grim port of MX-Project's FactoryML (Unlicense, Kireiko).
 *
 * <p>Differences from upstream: models load from Grim's bundled classpath resources
 * ({@code /ml/*.dat}) instead of the MX plugin data folder, and there is deliberately
 * NO "create fresh weights on failure" fallback — a missing/corrupt weight file yields
 * {@code null} so inference callers skip that model instead of flagging from random
 * weights. Training/persistence APIs are unsupported (inference only).</p>
 */
@UtilityClass
public class FactoryML {
    private static final Map<Integer, Millennium> CACHE = new ConcurrentHashMap<>();

    public static void createModel(int id, int tableSize, ModelVer ver) {
        createModel(id, tableSize, 10, ver);
    }

    public static Millennium createModel(int id, int tableSize, int stackSize, ModelVer ver) {
        Millennium m;
        switch (ver) {
            case VERSION_5:
                m = new RNNModelML(16, 48);
                break;
            default:
                m = new ModelML(tableSize, stackSize);
                break;
        }
        CACHE.put(id, m);
        return m;
    }

    public static Millennium getModel(int id) {
        return CACHE.get(id);
    }

    public static void removeModel(int id) {
        CACHE.remove(id);
    }

    /**
     * Inference-only Grim has no per-model data folder; persisting belongs to training
     * workflows which are not ported. Delegates to the bundled weights.
     */
    @SneakyThrows
    public static Millennium loadFromFile(int id, String name, int tSize, int sSize, ModelVer ver) {
        Logger.warn("loadFromFile is unsupported in the Grim port (inference only); loading bundled weights for " + name);
        return loadFromResources(id, name, tSize, sSize, ver);
    }

    @SneakyThrows
    public static Millennium loadFromResources(int id, String name, int tSize, int sSize, ModelVer ver) {
        String path = "/ml/" + name;

        try (InputStream is = FactoryML.class.getResourceAsStream(path)) {
            if (is != null) {
                if (ver == ModelVer.VERSION_5) {
                    RNNModelML m = new RNNModelML(16, 48);
                    m.load(is);
                    CACHE.put(id, m);
                    Logger.info("Model loaded from JAR: " + name);
                    return m;
                } else {
                    try (ObjectInputStream ois = new ObjectInputStream(is)) {
                        Millennium m = (Millennium) ois.readObject();
                        CACHE.put(id, m);
                        Logger.info("Model loaded from JAR: " + name);
                        return m;
                    }
                }
            } else {
                Logger.warn("Model resource missing: " + path + " (model " + id + " disabled)");
            }
        } catch (Exception e) {
            Logger.error("Failed to load model " + name + " (" + e + "); model " + id + " disabled.");
        }

        return null;
    }
}
