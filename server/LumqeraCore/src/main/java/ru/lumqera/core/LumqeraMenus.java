package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Read-only, holder-tagged inventories. Never trust inventory title or client-supplied slots. */
final class LumqeraMenus implements CommandExecutor, Listener {
    private enum Page { MAIN, SHOP, CLAN, PVP, CONFIRM }
    private static final class MenuHolder implements InventoryHolder {
        final Page page;
        final String offerId;
        Inventory inventory;
        MenuHolder(Page page, String offerId) { this.page = page; this.offerId = offerId; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final ShopCommands shop;
    private final ClansService clans;
    private final EconomyService economy;
    LumqeraMenus(JavaPlugin plugin, ShopCommands shop, ClansService clans, EconomyService economy) {
        this.plugin = plugin; this.shop = shop; this.clans = clans; this.economy = economy;
    }
    private Inventory create(Page page, String title, int size, String offerId) {
        MenuHolder holder = new MenuHolder(page, offerId);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        return inventory;
    }
    private ItemStack icon(Material material, String title, String... description) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            meta.setLore(List.of(description));
            stack.setItemMeta(meta);
        }
        return stack;
    }
    void openMain(Player player) {
        Inventory inv = create(Page.MAIN, "§5§lLUMQERA §8· Главное меню", 27, null);
        inv.setItem(10, icon(Material.SHIELD, "§dКлан", "§7Участники, база, казна"));
        inv.setItem(12, icon(Material.IRON_SWORD, "§dPvP и войны", "§7Рейтинги и сражения"));
        inv.setItem(14, icon(Material.EMERALD, "§aМагазин", "§7Предметы за монеты"));
        inv.setItem(16, icon(Material.COMPASS, "§bСпавн", "§7Телепортация с задержкой"));
        inv.setItem(24, icon(Material.CHEST, "§dОбмен предметами", "§7/trade request <ник>; /trade view", "§7Двойное подтверждение"));
        inv.setItem(22, icon(Material.GOLD_INGOT, "§6Баланс: " + economy.balance(player.getUniqueId()), "§7Личный кошелёк", "§7Переводы: /pay <ник> <сумма>"));
        player.openInventory(inv);
    }
    void openShop(Player player) {
        if (!player.hasPermission("lumqera.shop")) { player.sendMessage("§cНет доступа к магазину."); return; }
        Inventory inv = create(Page.SHOP, "§5§lLUMQERA §8· Магазин", 54, null);
        int slot = 0;
        for (ShopCatalog.Offer offer : shop.offers()) {
            if (slot >= 45) break;
            Material material = Material.matchMaterial(offer.material());
            if (material != null) inv.setItem(slot++, icon(material, "§e" + offer.id(),
                    "§7Набор: " + offer.amount() + " шт.", "§6Цена: " + offer.price() + " монет", "§aНажми для подтверждения"));
        }
        inv.setItem(49, icon(Material.ARROW, "§dНазад", "§7Главное меню"));
        player.openInventory(inv);
    }
    private void openClan(Player player) {
        Inventory inv = create(Page.CLAN, "§5§lLUMQERA §8· Клан", 27, null);
        ClanRegistry.Clan clan = clans.of(player.getUniqueId());
        inv.setItem(11, icon(Material.BOOK, clan == null ? "§cКлана пока нет" : "§d" + clan.name,
                clan == null ? "§7Создай: /clan create <имя>" : "§7Участники: " + clan.members.size(), "§7Информация: /clan info"));
        inv.setItem(13, icon(Material.RED_BED, "§bКлановая база", "§7/clan home"));
        inv.setItem(15, icon(Material.GOLD_BLOCK, "§6Клановая казна", "§7/treasury balance"));
        inv.setItem(22, icon(Material.ARROW, "§dНазад", "§7Главное меню"));
        player.openInventory(inv);
    }
    private void openPvp(Player player) {
        Inventory inv = create(Page.PVP, "§5§lLUMQERA §8· PvP", 27, null);
        inv.setItem(10, icon(Material.IRON_SWORD, "§cТвоя PvP-статистика", "§7/pvpstats"));
        inv.setItem(12, icon(Material.NETHER_STAR, "§eРейтинг", "§7/top pvp"));
        inv.setItem(14, icon(Material.SHIELD, "§dКлановые войны", "§7/war info"));
        inv.setItem(16, icon(Material.CLOCK, "§bБоевой таймер", "§7/combat"));
        inv.setItem(22, icon(Material.ARROW, "§dНазад", "§7Главное меню"));
        player.openInventory(inv);
    }
    private void confirm(Player player, String offerId) {
        ShopCatalog.Offer offer = shop.offers().stream().filter(o -> o.id().equals(offerId)).findFirst().orElse(null);
        if (offer == null) { openShop(player); return; }
        Inventory inv = create(Page.CONFIRM, "§5§lLUMQERA §8· Подтверждение", 27, offer.id());
        inv.setItem(11, icon(Material.LIME_WOOL, "§aКупить " + offer.id(),
                "§7Количество: " + offer.amount(), "§6Стоимость: " + offer.price(), "§7Оплата без дополнительного вопроса"));
        inv.setItem(15, icon(Material.RED_WOOL, "§cОтменить", "§7Вернуться в магазин"));
        player.openInventory(inv);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Меню доступно только игроку."); return true; }
        if (!player.hasPermission("lumqera.menu")) { player.sendMessage("§cНет доступа к меню."); return true; }
        if (args.length == 0) openMain(player);
        else if (args.length == 1 && args[0].equalsIgnoreCase("shop")) openShop(player);
        else player.sendMessage("§cИспользуй /menu или /menu shop");
        return true;
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true); // covers top, bottom, hotbar swaps and shift-click
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        if (event.getClick().isKeyboardClick() || event.getClick().isShiftClick()) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType().isAir()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            // Ignore delayed clicks if the player switched away from this exact menu.
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            switch (holder.page) {
                case MAIN -> {
                    switch (slot) {
                        case 10 -> openClan(player);
                        case 12 -> openPvp(player);
                        case 14 -> openShop(player);
                        case 16 -> { player.closeInventory(); player.performCommand("spawn"); }
                        case 22 -> { player.closeInventory(); player.performCommand("balance"); }
                        case 24 -> { player.closeInventory(); player.performCommand("trade view"); }
                        default -> { }
                    }
                }
                case SHOP -> {
                    if (slot == 49) { openMain(player); return; }
                    if (slot >= 45) return;
                    // Resolve slot against the authoritative shop catalog, not displayed NBT.
                    var available = shop.offers().stream()
                            .filter(o -> Material.matchMaterial(o.material()) != null).limit(45).toList();
                    if (slot < available.size()) confirm(player, available.get(slot).id());
                }
                case CONFIRM -> {
                    if (slot == 15) { openShop(player); return; }
                    if (slot == 11 && holder.offerId != null) {
                        player.closeInventory();
                        if (player.hasPermission("lumqera.shop")) shop.buy(player, holder.offerId, 1);
                    }
                }
                case CLAN -> {
                    String command = switch (slot) { case 11 -> "clan info"; case 13 -> "clan home";
                        case 15 -> "treasury balance"; default -> null; };
                    if (slot == 22) openMain(player);
                    else if (command != null) { player.closeInventory(); player.performCommand(command); }
                }
                case PVP -> {
                    String command = switch (slot) { case 10 -> "pvpstats"; case 12 -> "top pvp";
                        case 14 -> "war info"; case 16 -> "combat"; default -> null; };
                    if (slot == 22) openMain(player);
                    else if (command != null) { player.closeInventory(); player.performCommand(command); }
                }
            }
        });
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }
}
