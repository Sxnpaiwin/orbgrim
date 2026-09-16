package ac.grim.grimac.platform.bukkit.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Click routing for the Olympia log GUI. All clicks inside our menus are cancelled —
 * the GUI is inspection-only and must never move items.
 */
public final class OlympiaGuiListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BukkitOlympiaGui.Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (event.getRawSlot() < 0) return;

        int slot = event.getRawSlot();
        if (menu.kind == BukkitOlympiaGui.Kind.OFFENDERS) {
            if (slot < BukkitOlympiaGui.PAGE_SIZE) {
                int index = menu.page * BukkitOlympiaGui.PAGE_SIZE + slot;
                var offenders = ac.grim.grimac.manager.FlagFeed.INSTANCE
                        .topOffenders(BukkitOlympiaGui.PAGE_SIZE * 5);
                if (index >= 0 && index < offenders.size()) {
                    var offender = offenders.get(index);
                    BukkitOlympiaGui.openPlayer(viewer, offender.uuid(), offender.name(), 0);
                }
            } else if (slot == 45) {
                BukkitOlympiaGui.openOffenders(viewer, menu.page - 1);
            } else if (slot == 49) {
                viewer.closeInventory();
            } else if (slot == 53) {
                BukkitOlympiaGui.openOffenders(viewer, menu.page + 1);
            }
            return;
        }

        if (slot == 45) {
            BukkitOlympiaGui.openOffenders(viewer, 0);
        } else if (slot == 47) {
            BukkitOlympiaGui.openPlayer(viewer, menu.target, menu.targetName, menu.page - 1);
        } else if (slot == 49 && menu.target != null) {
            viewer.sendMessage(BukkitOlympiaGui.exportDebug(menu.target, menu.targetName));
            viewer.closeInventory();
        } else if (slot == 51 && menu.target != null) {
            BukkitOlympiaGui.openPlayer(viewer, menu.target, menu.targetName, menu.page + 1);
        } else if (slot == 53) {
            viewer.closeInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // No per-viewer state to clean: menus are stateless snapshots of FlagFeed.
    }
}
