package ru.lumqera.core;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Logger;

/** Explicit claim/unclaim actions: only clan leaders and officers can change land. */
final class LandCommands implements CommandExecutor, TabCompleter {
    private final ClansService clans;
    private final LandService land;
    private final TravelService travel;
    private final OutpostService outposts;
    private final int max;
    private final Logger logger;
    private final Set<String> allowedWorlds;
    LandCommands(ClansService clans, LandService land, TravelService travel, OutpostService outposts, int max, Collection<String> allowedWorlds, Logger logger) {
        this.clans = clans; this.land = land; this.travel = travel; this.outposts = outposts; this.max = Math.max(1, max); this.logger = logger;
        this.allowedWorlds = new HashSet<>(allowedWorlds);
    }
    private void send(CommandSender sender, String text) { sender.sendMessage("§5§lLUMQERA §8» §r" + text); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { send(sender, "§cКоманда доступна только игрокам."); return true; }
        if (!p.hasPermission("lumqera.claim")) { send(p, "§cНедостаточно прав."); return true; }
        ClanRegistry.Clan clan = clans.of(p.getUniqueId());
        if (clan == null) { send(p, "§cВступи в клан, чтобы управлять территорией."); return true; }
        String action = command.getName().toLowerCase(Locale.ROOT);
        if (action.equals("claims")) {
            send(p, "§d" + clan.name + " §7— занято чанков: §f" + land.state().count(clan.name) + "/" + max);
            for (var entry : land.state().allClaims()) {
                if (entry.getValue().equals(LandRegistry.key(clan.name))) {
                    var c = entry.getKey();
                    send(p, "§7 • X: §f" + c.x() + " §7Z: §f" + c.z() + " §7Мир: §f" + c.world().toString().substring(0, 8));
                }
            }
            return true;
        }
        if (!clan.isOfficerOrOwner(p.getUniqueId())) { send(p, "§cТерриторией управляют глава и офицеры."); return true; }
        if (!allowedWorlds.contains(p.getWorld().getName())) {
            send(p, "§cПриваты в этом мире отключены."); return true;
        }
        if (action.equals("claim") && outposts.state().all().stream().anyMatch(point ->
                OutpostService.intersects(point, LandService.chunk(p.getLocation())))) {
            send(p,"§cНельзя приватить PvP-точку и прилегающие к ней чанки."); return true;
        }
        LandRegistry.Result result = action.equals("claim") ? land.claim(clan, p.getLocation(), max) : land.unclaim(clan, p.getLocation());
        switch (result) {
            case OK -> {
                if (action.equals("unclaim")) travel.invalidateHomeIfUnclaimed(clan.name, land);
                var c = LandService.chunk(p.getLocation());
                logger.info("LAND " + action.toUpperCase(Locale.ROOT) + " actor=" + p.getUniqueId() + " clan=" + clan.name + " world=" + c.world() + " x=" + c.x() + " z=" + c.z());
                send(p, "§aЧанк " + c.x() + ", " + c.z() + (action.equals("claim") ? " занят кланом!" : " освобождён!"));
            }
            case SAFE_ZONE -> send(p, "§cЭтот чанк пересекается с безопасной зоной.");
            case ALREADY_CLAIMED -> send(p, "§cЭтот чанк уже занят.");
            case LIMIT_REACHED -> send(p, "§cДостигнут лимит: " + max + " чанков.");
            case NOT_ADJACENT -> send(p, "§cНовая территория должна граничить с вашей по стороне.");
            case NOT_OWNED -> send(p, "§cЭтот чанк не принадлежит твоему клану.");
            case DISCONNECTED -> send(p, "§cНельзя разделить территорию на несвязанные части.");
            case STORAGE_ERROR -> send(p, "§cНе удалось сохранить изменения территории. Сообщите администрации.");
            default -> send(p, "§cОперация отклонена.");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
}
