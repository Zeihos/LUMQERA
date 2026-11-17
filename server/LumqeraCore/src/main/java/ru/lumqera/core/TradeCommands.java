package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Online-only item-for-item trades: items are never placed in escrow or persisted as mutable offers. */
final class TradeCommands implements CommandExecutor, TabCompleter, Listener {
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    private final JavaPlugin plugin;
    private final TravelManager travel;
    private final TradeRegistry registry;
    private final Map<UUID, ItemStack> snapshots = new HashMap<>();
    private final double maxDistanceSquared;

    /** The holder, not the inventory title, identifies our read-only trade menu. */
    private static final class TradeViewHolder implements InventoryHolder {
        final TradeRegistry.Session snapshot;
        Inventory inventory;
        TradeViewHolder(TradeRegistry.Session snapshot) { this.snapshot = snapshot; }
        @Override public Inventory getInventory() { return inventory; }
    }

    TradeCommands(JavaPlugin plugin, TravelManager travel) {
        this.plugin = plugin;
        this.travel = travel;
        long invites = Math.max(5L, Math.min(600L, plugin.getConfig().getLong("trade.invite-seconds", 30)));
        long timeout = Math.max(10L, Math.min(900L, plugin.getConfig().getLong("trade.session-seconds", 120)));
        long inviteCooldown = Math.max(0L, Math.min(3600L, plugin.getConfig().getLong("trade.invite-cooldown-seconds", 5)));
        registry = new TradeRegistry(invites * 1000L, timeout * 1000L, inviteCooldown * 1000L);
        int range = Math.max(2, Math.min(32, plugin.getConfig().getInt("trade.max-distance", 8)));
        maxDistanceSquared = (double) range * range;
    }
    private void tell(CommandSender sender, String message) { sender.sendMessage(PREFIX + message); }
    private boolean nearby(Player a, Player b) {
        return a.isOnline() && b.isOnline() && a.getWorld().equals(b.getWorld())
                && a.getLocation().distanceSquared(b.getLocation()) <= maxDistanceSquared;
    }
    private boolean safe(Player a, Player b) {
        return nearby(a, b) && !a.isDead() && !b.isDead()
                && !travel.inCombat(a.getUniqueId()) && !travel.inCombat(b.getUniqueId());
    }
    private Player find(String name) {
        // Exact matching is deliberate; prefix completion must not resolve an unintended recipient.
        return Bukkit.getPlayerExact(name);
    }
    private Player partner(Player p, long now) {
        TradeRegistry.Session s = registry.session(p.getUniqueId(), now);
        return s == null ? null : Bukkit.getPlayer(s.partner(p.getUniqueId()));
    }
    private void cancel(UUID id, String reason) {
        long now = System.currentTimeMillis();
        TradeRegistry.Session session = registry.session(id, now);
        if (session != null) {
            snapshots.remove(session.first()); snapshots.remove(session.second());
            for (UUID p : List.of(session.first(), session.second())) {
                Player online = Bukkit.getPlayer(p);
                if (online != null) {
                    if (online.getOpenInventory().getTopInventory().getHolder() instanceof TradeViewHolder)
                        online.closeInventory();
                    tell(online, reason);
                }
            }
        }
        registry.cancel(id);
        snapshots.remove(id);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { tell(sender, "§cОбмен доступен только игрокам онлайн."); return true; }
        if (!player.hasPermission("lumqera.trade")) { tell(player, "§cНет доступа к обмену."); return true; }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> {
                tell(player, "§d/trade request <ник> §7— предложить обмен рядом (8 блоков по умолчанию)");
                tell(player, "§d/trade accept <ник> §7— принять запрос");
                tell(player, "§d/trade offer [кол-во] §7— предложить предмет в руке");
                tell(player, "§d/trade status §7— предложения участников");
                tell(player, "§d/trade view §7— безопасный просмотр обмена в меню");
                tell(player, "§d/trade confirm §7— подтвердить; §d/trade cancel §7— отменить");
            }
            case "request" -> {
                if (args.length != 2) { tell(player, "§c/trade request <ник>"); break; }
                Player other = find(args[1]);
                if (other == null) { tell(player, "§cИгрок не найден онлайн."); break; }
                if (!safe(player, other)) { tell(player, "§cПодойдите друг к другу и выйдите из PvP."); break; }
                TradeRegistry.Code result = registry.invite(id, other.getUniqueId(), now);
                if (result != TradeRegistry.Code.OK) {
                    tell(player, result == TradeRegistry.Code.COOLDOWN ? "§cПодожди перед следующим приглашением." : "§cЗапрос недоступен: " + result);
                    break;
                }
                tell(player, "§aЗапрос обмена отправлен: §f" + other.getName());
                tell(other, "§d" + player.getName() + " §fпредлагает обмен. §d/trade accept " + player.getName());
            }
            case "accept" -> {
                if (args.length != 2) { tell(player, "§c/trade accept <ник>"); break; }
                Player other = find(args[1]);
                if (other == null || !safe(player, other)) { tell(player, "§cИгрок недоступен или слишком далеко."); break; }
                TradeRegistry.Code code = registry.accept(id, other.getUniqueId(), now);
                if (code != TradeRegistry.Code.OK) { tell(player, "§cНет действующего запроса этого игрока."); break; }
                tell(player, "§aОбмен открыт. Предложи предмет: §f/trade offer [кол-во]§a, просмотр: §f/trade view");
                tell(other, "§a" + player.getName() + " принял обмен. §f/trade offer [кол-во]§a, просмотр: §f/trade view");
            }
            case "offer" -> {
                Player other = partner(player, now);
                if (other == null) { tell(player, "§cСначала начни обмен."); break; }
                if (!safe(player, other)) { cancel(id, "§eОбмен отменён: игроки далеко или в бою."); break; }
                if (args.length > 2) { tell(player, "§c/trade offer [кол-во]"); break; }
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir()) { tell(player, "§cДержи предмет в основной руке."); break; }
                int amount = held.getAmount();
                if (args.length == 2) {
                    try { if (!args[1].matches("[1-9][0-9]?") || Integer.parseInt(args[1]) > 64) throw new NumberFormatException(); amount = Integer.parseInt(args[1]); }
                    catch (NumberFormatException ex) { tell(player, "§cКоличество: целое число от 1 до 64."); break; }
                }
                if (amount > held.getAmount()) { tell(player, "§cВ руке меньше предметов, чем предлагаешь."); break; }
                TradeRegistry.Offer offer = new TradeRegistry.Offer(held.getType().name(), player.getInventory().getHeldItemSlot(), amount);
                TradeRegistry.Code code = registry.offer(id, offer, now);
                if (code != TradeRegistry.Code.OK) { tell(player, "§cОбмен закончился: " + code); break; }
                snapshots.put(id, held.clone());
                tell(player, "§aПредложено: §f" + amount + "x " + held.getType().name() + "§a. Подтверждения сброшены.");
                tell(other, "§e" + player.getName() + " обновил предложение. §f/trade status");
            }
            case "view", "gui" -> openPreview(player);
            case "status" -> {
                TradeRegistry.Session session = registry.session(id, now);
                if (session == null) { tell(player, "§7Активного обмена нет."); break; }
                tell(player, "§dОбмен с " + (partner(player, now) == null ? "неизвестно" : partner(player, now).getName()));
                for (UUID participant : List.of(session.first(), session.second())) {
                    TradeRegistry.Offer offer = session.offer(participant);
                    Player online = Bukkit.getPlayer(participant);
                    String who = online == null ? participant.toString() : online.getName();
                    tell(player, "§7" + who + ": §f" + (offer == null ? "нет предложения" : offer.amount()+"x "+offer.description())
                            + (session.ready(participant) ? " §a[ПОДТВЕРЖДЕНО]" : " §e[НЕ ПОДТВЕРЖДЕНО]"));
                }
            }
            case "confirm" -> {
                Player other = partner(player, now);
                if (other == null) { tell(player, "§cОбмен не открыт."); break; }
                if (!safe(player, other)) { cancel(id, "§eОбмен отменён: игроки далеко или в бою."); break; }
                TradeRegistry.Session current = registry.session(id, now);
                if (current == null || !offersStillValid(current)) { cancel(id, "§cОбмен отменён: предмет изменился или исчез."); break; }
                TradeRegistry.Code result = registry.confirm(id, now);
                if (result == TradeRegistry.Code.NOT_READY) { tell(player, "§cОба игрока должны предложить предмет."); break; }
                if (result == TradeRegistry.Code.OK) {
                    tell(player, "§aТы подтвердил обмен. Ожидаем второго участника.");
                    tell(other, "§e"+ player.getName() + " подтвердил. Проверь §f/trade status§e и используй §f/trade confirm");
                } else if (result == TradeRegistry.Code.BOTH_READY) {
                    complete(player, other, registry.session(id, now));
                } else tell(player, "§cОбмен недоступен: " + result);
            }
            case "cancel" -> {
                boolean existing = registry.session(id, now) != null || registry.invitation(id, now) != null;
                cancel(id, "§eОбмен отменён участником.");
                if (!existing) tell(player, "§7Активный обмен или запрос отменён (если был). ");
            }
            default -> tell(player, "§c/trade help");
        }
        return true;
    }
    private static ItemStack labeled(Material type, String title, String... lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            meta.setLore(List.of(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
    private void openPreview(Player viewer) {
        TradeRegistry.Session session = registry.session(viewer.getUniqueId(), System.currentTimeMillis());
        if (session == null) {
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof TradeViewHolder)
                viewer.closeInventory();
            tell(viewer, "§cНет активного обмена. /trade request <ник>");
            return;
        }
        Player first = Bukkit.getPlayer(session.first()), second = Bukkit.getPlayer(session.second());
        if (first == null || second == null || !safe(first, second)) {
            cancel(viewer.getUniqueId(), "§cОбмен отменён: участники недоступны."); return;
        }
        if (session.firstOffer() != null && session.secondOffer() != null && !offersStillValid(session)) {
            cancel(viewer.getUniqueId(), "§cОбмен отменён: предметы были изменены."); return;
        }
        TradeViewHolder holder = new TradeViewHolder(session);
        Inventory preview = Bukkit.createInventory(holder, 45, "§5§lLUMQERA §8· Просмотр обмена");
        holder.inventory = preview;
        for (int index = 0; index < 2; index++) {
            UUID id = index == 0 ? session.first() : session.second();
            TradeRegistry.Offer offer = session.offer(id);
            Player participant = index == 0 ? first : second;
            int slot = index == 0 ? 11 : 15;
            preview.setItem(slot - 9, labeled(Material.NAME_TAG, "§d" + participant.getName(),
                    session.ready(id) ? "§aПодтверждено" : "§eНе подтверждено"));
            ItemStack snapshot = snapshots.get(id);
            if (offer != null && snapshot != null) {
                ItemStack display = snapshot.clone();
                display.setAmount(offer.amount());
                preview.setItem(slot, display);
            } else preview.setItem(slot, labeled(Material.BARRIER, "§7Предмет не предложен", "§7/trade offer <кол-во>"));
        }
        preview.setItem(36, labeled(Material.ARROW, "§bОбновить предложения", "§7Нажми, чтобы увидеть актуальный статус"));
        preview.setItem(40, labeled(Material.LIME_WOOL, "§aПодтвердить обмен", "§7Только после проверки обоих предметов"));
        preview.setItem(44, labeled(Material.RED_WOOL, "§cОтменить обмен", "§7Без потери предметов"));
        viewer.openInventory(preview);
    }
    @EventHandler public void onPreviewClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeViewHolder holder)) return;
        event.setCancelled(true); // Includes shift-clicks and clicks into the player's inventory.
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        int slot = event.getRawSlot();
        if (slot != 36 && slot != 40 && slot != 44) return;
        if (event.getClick().isKeyboardClick() || event.getClick().isShiftClick()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (viewer.getOpenInventory().getTopInventory().getHolder() != holder) return;
            // A change in either offer or confirmation invalidates the displayed snapshot.
            TradeRegistry.Session live = registry.session(viewer.getUniqueId(), System.currentTimeMillis());
            if (slot == 36 || (slot == 40 && live != holder.snapshot)) {
                if (slot == 40) tell(viewer, "§eУсловия обмена изменились. Проверь обновлённое предложение.");
                openPreview(viewer);
                return;
            }
            viewer.closeInventory();
            if (slot == 40) viewer.performCommand("trade confirm");
            else if (slot == 44) viewer.performCommand("trade cancel");
        });
    }
    @EventHandler public void onPreviewDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeViewHolder) event.setCancelled(true);
    }
    private boolean validFor(Player player, TradeRegistry.Offer offer) {
        if (offer == null || player == null || !player.isOnline()) return false;
        ItemStack old = snapshots.get(player.getUniqueId());
        if (old == null) return false;
        PlayerInventory inv = player.getInventory();
        ItemStack[] slots = inv.getStorageContents();
        if (offer.slot() >= slots.length) return false;
        ItemStack current = slots[offer.slot()];
        return current != null && !current.getType().isAir() && current.isSimilar(old) && current.getAmount() >= offer.amount();
    }
    private boolean offersStillValid(TradeRegistry.Session session) {
        Player a = Bukkit.getPlayer(session.first()), b = Bukkit.getPlayer(session.second());
        return validFor(a, session.firstOffer()) && validFor(b, session.secondOffer());
    }
    private static ItemStack[] copy(ItemStack[] src) {
        ItemStack[] dst = new ItemStack[src.length];
        for (int i=0; i<src.length; i++) dst[i] = src[i] == null ? null : src[i].clone();
        return dst;
    }
    /** Mutates only scratch storage: first consume the offered stack, then pack incoming items. */
    static boolean pack(ItemStack[] scratch, int sourceSlot, int outgoing, ItemStack incoming) {
        ItemStack source = scratch[sourceSlot];
        if (source == null || source.getAmount() < outgoing || outgoing < 1) return false;
        if (source.getAmount() == outgoing) scratch[sourceSlot] = null;
        else { source.setAmount(source.getAmount() - outgoing); }
        int remaining = incoming.getAmount();
        for (int i=0; i<scratch.length && remaining>0; i++) {
            ItemStack stack = scratch[i];
            if (stack == null || !stack.isSimilar(incoming)) continue;
            int add = Math.min(remaining, Math.max(0, Math.min(stack.getMaxStackSize(), 64) - stack.getAmount()));
            if (add > 0) { stack.setAmount(stack.getAmount()+add); remaining-=add; }
        }
        for (int i=0; i<scratch.length && remaining>0; i++) {
            if (scratch[i] != null && !scratch[i].getType().isAir()) continue;
            int add = Math.min(remaining, Math.min(incoming.getMaxStackSize(), 64));
            ItemStack piece = incoming.clone(); piece.setAmount(add); scratch[i] = piece; remaining-=add;
        }
        return remaining == 0;
    }
    private void complete(Player confirmer, Player partner, TradeRegistry.Session session) {
        if (session == null) return;
        Player first = Bukkit.getPlayer(session.first()), second = Bukkit.getPlayer(session.second());
        if (first == null || second == null || !safe(first, second) || !offersStillValid(session)) {
            cancel(confirmer.getUniqueId(), "§cОбмен отменён: предметы или условия изменились."); return;
        }
        ItemStack[] aBefore = copy(first.getInventory().getStorageContents());
        ItemStack[] bBefore = copy(second.getInventory().getStorageContents());
        TradeRegistry.Offer aOffer = session.firstOffer(), bOffer = session.secondOffer();
        ItemStack[] aAfter = copy(aBefore), bAfter = copy(bBefore);
        ItemStack aGive = aBefore[aOffer.slot()].clone(); aGive.setAmount(aOffer.amount());
        ItemStack bGive = bBefore[bOffer.slot()].clone(); bGive.setAmount(bOffer.amount());
        // Each player receives the OTHER player's offered stack, preserving item metadata.
        if (!pack(aAfter, aOffer.slot(), aOffer.amount(), bGive) ||
                !pack(bAfter, bOffer.slot(), bOffer.amount(), aGive)) {
            cancel(confirmer.getUniqueId(), "§cОбмен отменён: недостаточно места в инвентаре."); return;
        }
        // Sync server thread: both plans were validated before the first real inventory mutation.
        try {
            first.getInventory().setStorageContents(aAfter);
            second.getInventory().setStorageContents(bAfter);
        } catch (RuntimeException error) {
            try {
                first.getInventory().setStorageContents(aBefore);
                second.getInventory().setStorageContents(bBefore);
            } catch (RuntimeException rollbackError) {
                plugin.getLogger().severe("TRADE ROLLBACK FAILED, MANUAL REVIEW REQUIRED: "+session.first()+" "+session.second()+": "+rollbackError);
            }
            plugin.getLogger().severe("TRADE INVENTORY ERROR: "+error);
            cancel(confirmer.getUniqueId(), "§cОбмен прерван ошибкой инвентаря. Сообщите администрации.");
            return;
        }
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            String log = System.currentTimeMillis()+" first="+session.first()+" second="+session.second()+
                    " gave="+aOffer.amount()+"x"+aGive.getType()+" received="+bOffer.amount()+"x"+bGive.getType()+"\n";
            Files.writeString(plugin.getDataFolder().toPath().resolve("trade-audit.log"), log, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) { plugin.getLogger().warning("Trade finished but audit log write failed: "+ex.getMessage()); }
        registry.cancel(confirmer.getUniqueId());
        snapshots.remove(session.first()); snapshots.remove(session.second());
        tell(first, "§aОбмен завершён: получено §f"+bOffer.amount()+"x "+bGive.getType());
        tell(second, "§aОбмен завершён: получено §f"+aOffer.amount()+"x "+aGive.getType());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer().getUniqueId(), "§eОбмен отменён: игрок вышел."); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { cancel(event.getEntity().getUniqueId(), "§eОбмен отменён: игрок погиб."); }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0 &&
                registry.session(player.getUniqueId(), System.currentTimeMillis()) != null)
            cancel(player.getUniqueId(), "§eОбмен отменён: участник получил урон.");
    }
    void tick() {
        long now=System.currentTimeMillis();
        Set<UUID> before=registry.unprunedPlayers();
        Set<UUID> after=registry.activePlayers(now);
        for (UUID id: before) if (!after.contains(id)) {
            snapshots.remove(id);
            Player online=Bukkit.getPlayer(id);
            if (online!=null) tell(online, "§eВремя обмена истекло.");
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length==1) return List.of("help","request","accept","offer","status","view","confirm","cancel").stream()
                .filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length==2 && (args[0].equalsIgnoreCase("request") || args[0].equalsIgnoreCase("accept")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s->s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
