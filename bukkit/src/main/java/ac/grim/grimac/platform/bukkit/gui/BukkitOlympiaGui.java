package ac.grim.grimac.platform.bukkit.gui;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.OlympiaGuiBridge;
import ac.grim.grimac.manager.FlagFeed;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bukkit side of the Olympia log GUI: chest menus over {@link FlagFeed} plus per-player
 * debug-file export for hand-off to developers.
 */
public final class BukkitOlympiaGui implements OlympiaGuiBridge.Opener {

    static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Menu state carried by the inventory holder. */
    public enum Kind {OFFENDERS, PLAYER}

    public static final class Menu implements InventoryHolder {
        final Kind kind;
        final int page;
        final UUID target;
        final String targetName;
        private Inventory inventory;

        Menu(Kind kind, int page, UUID target, String targetName) {
            this.kind = kind;
            this.page = page;
            this.target = target;
            this.targetName = targetName;
        }

        @NotNull
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void openOffenders(Sender sender) {
        Player viewer = Bukkit.getPlayer(sender.getUniqueId());
        if (viewer == null) return;
        openOffenders(viewer, 0);
    }

    @Override
    public void openPlayer(Sender sender, String targetName) {
        Player viewer = Bukkit.getPlayer(sender.getUniqueId());
        if (viewer == null) return;
        Player online = Bukkit.getPlayerExact(targetName);
        UUID uuid;
        String name;
        if (online != null) {
            uuid = online.getUniqueId();
            name = online.getName();
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
            uuid = offline.getUniqueId();
            name = offline.getName() != null ? offline.getName() : targetName;
        }
        openPlayer(viewer, uuid, name, 0);
    }

    static void openOffenders(Player viewer, int page) {
        List<FlagFeed.Offender> offenders = FlagFeed.INSTANCE.topOffenders(PAGE_SIZE * 5);
        int pages = Math.max(1, (int) Math.ceil(offenders.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        Menu holder = new Menu(Kind.OFFENDERS, page, null, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "Olympia | Offenders (" + (page + 1) + "/" + pages + ")");
        holder.inventory = inv;

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < offenders.size(); i++) {
            FlagFeed.Offender offender = offenders.get(from + i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(offender.uuid()));
                meta.setDisplayName("§6" + offender.name());
                List<String> lore = new ArrayList<>();
                lore.add("§7Flags: §e" + offender.flags());
                lore.add("§7Click to inspect.");
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(i, head);
        }

        if (offenders.isEmpty()) {
            inv.setItem(22, named(Material.BARRIER, "§7No flags recorded yet",
                    List.of("§7Flags appear here as checks trip.")));
        }

        if (page > 0) inv.setItem(45, named(Material.ARROW, "§ePrevious page", null));
        inv.setItem(49, named(Material.BARRIER, "§cClose", null));
        if (page < pages - 1) inv.setItem(53, named(Material.ARROW, "§eNext page", null));
        viewer.openInventory(inv);
    }

    static void openPlayer(Player viewer, UUID uuid, String name, int page) {
        List<FlagFeed.Entry> all = new ArrayList<>(FlagFeed.INSTANCE.recentFor(uuid, 100));
        all.sort((a, b) -> Long.compare(b.time(), a.time()));
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        Menu holder = new Menu(Kind.PLAYER, page, uuid, name);
        Inventory inv = Bukkit.createInventory(holder, 54, "Olympia | " + name + " (" + (page + 1) + "/" + pages + ")");
        holder.inventory = inv;

        int from = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && from + i < all.size(); i++) {
            FlagFeed.Entry entry = all.get(from + i);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + TIME.format(Instant.ofEpochMilli(entry.time())) + " §8| §7VL §e" + entry.vl());
            lore.addAll(wrap("§f" + entry.verbose(), 40));
            inv.setItem(i, named(Material.PAPER, "§6" + entry.check(), lore));
        }

        if (all.isEmpty()) {
            inv.setItem(22, named(Material.BARRIER, "§7No flags for " + name,
                    List.of("§7Nothing recorded in this session.")));
        }

        inv.setItem(45, named(Material.ARROW, "§eBack to offenders", null));
        if (page > 0) inv.setItem(47, named(Material.ARROW, "§ePrevious page", null));
        if (!all.isEmpty()) {
            inv.setItem(49, named(Material.EMERALD, "§aExport debug file",
                    List.of("§7Writes plugins/OlympiaAC/debug/", "§7Send it to the developer.")));
        }
        if (page < pages - 1) inv.setItem(51, named(Material.ARROW, "§eNext page", null));
        inv.setItem(53, named(Material.BARRIER, "§cClose", null));
        viewer.openInventory(inv);
    }

    /** Writes the developer debug bundle and returns a human-readable result line. */
    static String exportDebug(UUID uuid, String name) {
        try {
            Path dir = GrimACBukkitLoaderPlugin.LOADER.getDataFolder().toPath().resolve("debug");
            Files.createDirectories(dir);
            Path file = dir.resolve(name + "-" + System.currentTimeMillis() + ".txt");

            StringBuilder sb = new StringBuilder();
            sb.append("Olympia debug export\n");
            sb.append("player: ").append(name).append(" (").append(uuid).append(")\n");
            sb.append("exported: ").append(Instant.now()).append('\n');
            try {
                sb.append("version: ").append(GrimAPI.INSTANCE.getExternalAPI().getGrimVersion()).append('\n');
            } catch (Exception e) {
                sb.append("version: unknown\n");
            }
            sb.append("toggles:\n");
            for (String key : toggleKeys()) {
                sb.append("  ").append(key).append(" = ").append(readToggle(key)).append('\n');
            }
            sb.append("flags (newest last):\n");
            List<FlagFeed.Entry> entries = new ArrayList<>(FlagFeed.INSTANCE.recentFor(uuid, 100));
            entries.sort((a, b) -> Long.compare(a.time(), b.time()));
            if (entries.isEmpty()) sb.append("  (none)\n");
            for (FlagFeed.Entry entry : entries) {
                sb.append("  [").append(Instant.ofEpochMilli(entry.time())).append("] ")
                        .append(entry.check()).append(" vl=").append(entry.vl())
                        .append(' ').append(entry.verbose()).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return "§aExported " + entries.size() + " flags to §f" + file.getFileName();
        } catch (Exception e) {
            return "§cExport failed: " + e.getMessage();
        }
    }

    private static List<String> toggleKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("experimental-checks");
        keys.add("MX.aim-entropy.enable");
        keys.add("MX.aim-heuristics.enable");
        keys.add("MX.aim-ml.enable");
        String[] groups = {"sensitivity", "exact-aim", "accuracy", "corner-aim", "aim-stdev",
                "follow-aim", "modulo-reset", "snap-aim", "attack-required", "pre-attack",
                "tool-switch", "sent-slot-twice", "civbreak", "blocking",
                "click-speed", "click-deviation", "click-entropy", "click-kurtosis",
                "click-fluctuation", "click-repetitive", "click-bursts", "triggerbot"};
        for (String group : groups) keys.add("Intave." + group + ".enable");
        keys.add("Intave.click-speed.max-cps");
        keys.add("Intave.sent-slot-twice.vl");
        keys.add("Intave.triggerbot.max-mean-ms");
        keys.add("Intave.triggerbot.max-stddev-ms");
        return keys;
    }

    private static String readToggle(String key) {
        try {
            var config = GrimAPI.INSTANCE.getConfigManager().getConfig();
            if (key.endsWith(".enable") || key.equals("experimental-checks")) {
                return String.valueOf(config.getBooleanElse(key, true));
            }
            if (key.endsWith("max-cps")) {
                return String.valueOf(config.getIntElse(key, 20));
            }
            return String.valueOf(config.getDoubleElse(key, 0));
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    private static ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        String remaining = text == null ? "" : text;
        if (remaining.isEmpty()) {
            lines.add("§8(no details)");
            return lines;
        }
        while (remaining.length() > width) {
            int cut = remaining.lastIndexOf(' ', width);
            if (cut <= 0) cut = width;
            lines.add(remaining.substring(0, cut));
            remaining = "§f" + remaining.substring(cut).stripLeading();
        }
        lines.add(remaining);
        return lines;
    }
}
