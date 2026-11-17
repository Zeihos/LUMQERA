package ru.lumqera.core;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Limited buy-only shop. No external GUI, dynamic player listings, or arbitrary item NBT. */
final class ShopCommands implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    private final EconomyService economy;
    private final ShopCatalog catalog;
    private final int maxBundles;
    private final Logger logger;
    ShopCommands(EconomyService economy, ConfigurationSection configuration, int maxBundles, Logger logger) {
        this.economy = economy;
        this.maxBundles = Math.max(1, Math.min(64, maxBundles));
        this.logger = logger;
        List<ShopCatalog.Offer> offers = new ArrayList<>();
        if (configuration != null) for (String key : configuration.getKeys(false)) {
            ConfigurationSection offer = configuration.getConfigurationSection(key);
            if (offer == null) continue;
            String materialName = offer.getString("material", "").toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(materialName);
            if (material == null || !material.isItem()) {
                logger.warning("Shop item disabled; invalid material: " + key); continue;
            }
            try {
                ShopCatalog.Offer candidate = new ShopCatalog.Offer(key.toLowerCase(Locale.ROOT),
                        material.name(), offer.getInt("amount"), offer.getLong("price"));
                new ShopCatalog(List.of(candidate)); // strict validation before adding
                offers.add(candidate);
            } catch (IllegalArgumentException ex) { logger.warning("Shop offer disabled: " + key); }
        }
        this.catalog = new ShopCatalog(offers);
    }
    List<ShopCatalog.Offer> offers() { return List.copyOf(catalog.offers()); }
    private void tell(CommandSender sender, String message) { sender.sendMessage(PREFIX + message); }
    /** Capacity check excludes armor/offhand, and counts existing matching stacks. */
    private boolean fits(PlayerInventory inventory, Material material, int count) {
        long space = 0;
        ItemStack expected = new ItemStack(material);
        int max = material.getMaxStackSize();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) space += max;
            else if (stack.isSimilar(expected)) space += Math.max(0, max - stack.getAmount());
            if (space >= count) return true;
        }
        return false;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { tell(sender, "§cМагазин доступен в игре."); return true; }
        if (!player.hasPermission("lumqera.shop")) { tell(sender, "§cНет разрешения."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            tell(sender, "§dМагазин LUMQERA §7(покупка предметов за монеты)");
            if (catalog.offers().isEmpty()) tell(sender, "§eПредложений пока нет.");
            for (ShopCatalog.Offer offer : catalog.offers())
                tell(sender, "§d" + offer.id() + " §7— §f" + offer.amount() + "x " + offer.material() + " §7за §6" + offer.price());
            tell(sender, "§7Покупка: §d/shop buy <товар> [количество наборов]");
            return true;
        }
        if (!args[0].equalsIgnoreCase("buy") || (args.length != 2 && args.length != 3)) {
            tell(sender, "§cИспользуй /shop list или /shop buy <товар> [наборы]"); return true;
        }
        int bundles;
        try { bundles = args.length == 3 && args[2].matches("[0-9]{1,3}") ? Integer.parseInt(args[2]) : (args.length == 2 ? 1 : 0); }
        catch (NumberFormatException ex) { bundles = 0; }
        buy(player, args[1], bundles);
        return true;
    }
    /** Shared purchase path for both /shop and the GUI. Always rechecks money and inventory. */
    boolean buy(Player player, String offerId, int bundles) {
        if (!player.hasPermission("lumqera.shop")) { tell(player, "§cНет разрешения."); return false; }
        ShopCatalog.Quote quote = catalog.quote(offerId, bundles, maxBundles);
        if (quote == null) { tell(player, "§cНет такого товара или превышен лимит наборов (" + maxBundles + ")."); return false; }
        Material material = Material.matchMaterial(quote.offer().material());
        if (material == null || !fits(player.getInventory(), material, quote.items())) {
            tell(player, "§cНедостаточно места в инвентаре для всей покупки."); return false;
        }
        if (!economy.ensure(player.getUniqueId())) { tell(player, "§cКошелёк недоступен."); return false; }
        EconomyRegistry.Code result = economy.purchase(player.getUniqueId(), quote.offer().id(), quote.coins());
        if (result != EconomyRegistry.Code.OK) {
            tell(player, result == EconomyRegistry.Code.INSUFFICIENT ? "§cНе хватает монет." : "§cНе удалось оплатить: " + result);
            return false;
        }
        // Preserve the exact pre-delivery storage: removing by material would also
        // remove pre-existing items, including possibly stacks with different metadata.
        ItemStack[] before = player.getInventory().getStorageContents();
        ItemStack[] snapshot = new ItemStack[before.length];
        for (int i = 0; i < before.length; i++)
            snapshot[i] = before[i] == null ? null : before[i].clone();
        boolean deliveryFailed;
        try {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material, quote.items()));
            deliveryFailed = !leftovers.isEmpty();
        } catch (RuntimeException ex) {
            logger.severe("SHOP DELIVERY ERROR player=" + player.getUniqueId() + " error=" + ex);
            deliveryFailed = true;
        }
        if (deliveryFailed) {
            boolean restored = true;
            try { player.getInventory().setStorageContents(snapshot); }
            catch (RuntimeException ex) {
                restored = false;
                logger.severe("SHOP INVENTORY ROLLBACK FAILED player=" + player.getUniqueId() + " error=" + ex);
            }
            // Refund only when the inventory was restored: otherwise a refund can
            // turn an incomplete delivery into duplicated items and money.
            EconomyRegistry.Code refund = restored
                    ? economy.refundPurchase(player.getUniqueId(), quote.offer().id(), quote.coins())
                    : EconomyRegistry.Code.STORAGE_ERROR;
            if (refund != EconomyRegistry.Code.OK) logger.severe("MANUAL SHOP RECONCILIATION REQUIRED: player="
                    + player.getUniqueId() + " offer=" + quote.offer().id() + " coins=" + quote.coins());
            tell(player, refund == EconomyRegistry.Code.OK ? "§cНе удалось выдать товар; монеты возвращены."
                    : "§cОшибка выдачи или возврата. Обратись к администрации!");
            return false;
        }
        tell(player, "§aКуплено §f" + quote.items() + "x " + material.name() + " §aза §6" + quote.coins() + " монет.");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("list", "buy").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("buy"))
            return catalog.offers().stream().map(ShopCatalog.Offer::id)
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
