package ac.grim.grimac.command;

import ac.grim.grimac.platform.api.sender.Sender;
import org.jetbrains.annotations.Nullable;

/**
 * Olympia GUI bridge: the {@code /olympia} command tree lives in common, but chest
 * inventories need platform APIs. The platform module (Bukkit) installs an opener at
 * startup; on platforms without one the command reports unavailable instead of
 * crashing (Fabric-safe).
 */
public final class OlympiaGuiBridge {

    public interface Opener {
        void openOffenders(Sender sender);

        void openPlayer(Sender sender, String targetName);
    }

    private static volatile @Nullable Opener opener;

    private OlympiaGuiBridge() {}

    public static void install(Opener opener) {
        OlympiaGuiBridge.opener = opener;
    }

    public static boolean open(Sender sender, @Nullable String targetName) {
        Opener opener = OlympiaGuiBridge.opener;
        if (opener == null) {
            return false;
        }
        if (targetName == null) {
            opener.openOffenders(sender);
        } else {
            opener.openPlayer(sender, targetName);
        }
        return true;
    }
}
