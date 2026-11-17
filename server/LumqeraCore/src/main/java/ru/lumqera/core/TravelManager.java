package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.*;

/** Warmups, combat tags and safe teleport validation; all called on the server thread. */
final class TravelManager implements Listener, CommandExecutor {
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    private final JavaPlugin plugin;
    private final ClansService clans;
    private final LandService land;
    private final TravelService travel;
    private final CombatTagRegistry combat = new CombatTagRegistry();
    private final Set<UUID> authorizedTeleports = new HashSet<>();
    private final long tagMs;
    private final long warmupMs;
    private final boolean allowInCombatBypass;

    TravelManager(JavaPlugin plugin, ClansService clans, LandService land, TravelService travel) {
        this.plugin = plugin; this.clans = clans; this.land = land; this.travel = travel;
        this.tagMs = Math.max(0, plugin.getConfig().getLong("travel.combat-tag-seconds", 20)) * 1000L;
        this.warmupMs = Math.max(0, plugin.getConfig().getLong("travel.warmup-seconds", 5)) * 1000L;
        this.allowInCombatBypass = plugin.getConfig().getBoolean("travel.admin-bypass-combat", false);
    }
    private void tell(CommandSender p, String message) { p.sendMessage(PREFIX + message); }
    boolean inCombat(UUID id) { return combat.active(id, System.currentTimeMillis()); }
    private boolean combatBlocked(Player p) {
        return combat.active(p.getUniqueId(), System.currentTimeMillis()) && !(allowInCombatBypass && p.hasPermission("lumqera.admin"));
    }
    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player p) return p;
        }
        return null;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { tell(sender, "§cТолько в игре."); return true; }
        if (command.getName().equalsIgnoreCase("combat")) {
            long sec = combat.remainingSeconds(p.getUniqueId(), System.currentTimeMillis());
            tell(p, sec == 0 ? "§aТы не участвуешь в бою." : "§cPvP-режим: ещё §f" + sec + "§c сек. Телепортация запрещена.");
            return true;
        }
        if (!p.hasPermission("lumqera.spawn")) { tell(p, "§cНедостаточно прав."); return true; }
        return request(p, TravelRegistry.Destination.SPAWN, null);
    }
    boolean clanCommand(Player p, ClanRegistry.Clan clan, String action) {
        if (clan == null) { tell(p, "§cСначала вступи в клан."); return true; }
        if (action.equals("home")) return request(p, TravelRegistry.Destination.CLAN_HOME, clan.name);
        if (!clan.isOfficerOrOwner(p.getUniqueId())) { tell(p, "§cТолько глава и офицеры управляют базой."); return true; }
        if (action.equals("delhome")) {
            if (travel.state().deleteHome(clan.name)) { travel.save(); tell(p, "§aКлановая база удалена."); }
            else tell(p, "§cКлановая база не установлена.");
            return true;
        }
        if (combatBlocked(p)) { tell(p, "§cНельзя изменять клановую базу во время PvP."); return true; }
        if (!LandRegistry.key(clan.name).equals(land.owner(p.getLocation())) || land.zone(p.getLocation()) != null) {
            tell(p, "§cБазу можно установить только внутри собственного приватного чанка."); return true;
        }
        Location chosen = TravelService.centered(p.getLocation());
        if (!TravelService.safe(chosen)) { tell(p, "§cВстань на твёрдый блок, в месте с двумя свободными блоками высоты."); return true; }
        travel.state().setHome(clan.name, TravelService.point(chosen));
        travel.save();
        tell(p, "§aКлановая база установлена. Участники могут использовать §f/clan home§a.");
        return true;
    }
    void adminSetSpawn(CommandSender sender) {
        if (!(sender instanceof Player p)) { tell(sender, "§cКоманда выполняется в игре."); return; }
        if (land.zone(p.getLocation()) == null) { tell(p, "§cСначала создайте безопасную зону: /lumqera zone set spawn 128"); return; }
        Location chosen = TravelService.centered(p.getLocation());
        if (!TravelService.safe(chosen)) { tell(p, "§cНебезопасная точка: необходим твёрдый пол и свободное место."); return; }
        travel.state().setSpawn(TravelService.point(chosen));
        travel.save();
        plugin.getLogger().info("SPAWN SET actor=" + p.getUniqueId() + " world=" + p.getWorld().getUID());
        tell(p, "§aТочка спавна установлена. Команда игроков: §f/spawn§a.");
    }
    void spawnInfo(CommandSender sender) {
        TravelRegistry.Point point = travel.state().spawn();
        tell(sender, point == null ? "§7Спавн ещё не задан." : "§dСпавн: §f" + point.world() + " §7X=" + (int)point.x() + " Y=" + (int)point.y() + " Z=" + (int)point.z());
    }
    private Location destination(TravelRegistry.Destination kind, String clan) {
        if (kind == TravelRegistry.Destination.SPAWN) {
            Location loc = travel.location(travel.state().spawn());
            return loc != null && land.zone(loc) != null ? loc : null;
        }
        return travel.validHome(clan, land) ? travel.location(travel.state().home(clan)) : null;
    }
    private boolean request(Player p, TravelRegistry.Destination kind, String clan) {
        if (combatBlocked(p)) { tell(p, "§cВо время PvP телепортация запрещена. §f/combat"); return true; }
        Location target = destination(kind, clan);
        if (!TravelService.safe(target)) { tell(p, "§cТочка не задана, недоступна или небезопасна. Сообщи администрации."); return true; }
        travel.state().begin(p.getUniqueId(), kind, clan, TravelService.point(p.getLocation()), System.currentTimeMillis(), warmupMs);
        tell(p, "§dТелепортация через §f" + (warmupMs / 1000) + "§d сек. Не двигайся и не получай урон.");
        return true;
    }
    void tick() {
        long now = System.currentTimeMillis();
        combat.prune(now);
        for (UUID id : travel.state().waitingPlayers()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) { travel.state().cancel(id); continue; }
            TravelRegistry.Request req = travel.state().pending(id);
            if (req == null) continue;
            if (travel.state().cancelIfMoved(id, TravelService.point(player.getLocation()), 0.35)) {
                tell(player, "§eТелепортация отменена: ты сдвинулся."); continue;
            }
            if (now < req.readyAt()) continue;
            travel.state().cancel(id);
            if (combatBlocked(player)) { tell(player, "§cТелепортация отменена из-за PvP."); continue; }
            if (req.destination() == TravelRegistry.Destination.CLAN_HOME) {
                ClanRegistry.Clan currentClan = clans.of(id);
                if (currentClan == null || !LandRegistry.key(currentClan.name).equals(LandRegistry.key(req.clanKey()))) {
                    tell(player, "§cКлан изменился: телепортация отменена."); continue;
                }
            }
            Location dest = destination(req.destination(), req.clanKey());
            if (!TravelService.safe(dest)) { tell(player, "§cМесто назначения стало небезопасным."); continue; }
            authorizedTeleports.add(id);
            try {
                if (player.teleport(dest)) tell(player, "§aТелепортация выполнена!");
                else tell(player, "§cТелепортация отменена сервером.");
            } finally { authorizedTeleports.remove(id); }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || e.getFinalDamage() <= 0) return;
        Player source = attacker(e.getDamager());
        if (source == null || source.equals(victim)) return;
        if (!plugin.getConfig().getBoolean("clans.friendly-fire", false)
                && clans.sameClan(source.getUniqueId(), victim.getUniqueId())) return;
        if (land.zone(source.getLocation()) != null || land.zone(victim.getLocation()) != null) return;
        long now = System.currentTimeMillis();
        boolean firstTag = !combat.active(victim.getUniqueId(), now);
        combat.tag(source.getUniqueId(), victim.getUniqueId(), now, tagMs);
        if (travel.state().cancel(source.getUniqueId())) tell(source, "§eТелепортация отменена: PvP.");
        if (travel.state().cancel(victim.getUniqueId())) tell(victim, "§eТелепортация отменена: PvP.");
        if (firstTag && tagMs > 0) tell(victim, "§cТы вступил в бой. /combat — проверить оставшееся время.");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getFinalDamage() > 0 && travel.state().cancel(p.getUniqueId()))
            tell(p, "§eТелепортация отменена: получен урон.");
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to == null || (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY()
                && e.getFrom().getBlockZ() == to.getBlockZ() && e.getFrom().getWorld().equals(to.getWorld()))) return;
        if (combatBlocked(e.getPlayer()) && land.zone(e.getFrom()) == null && land.zone(to) != null) {
            e.setCancelled(true);
            return;
        }
        if (travel.state().cancelIfMoved(e.getPlayer().getUniqueId(), TravelService.point(to), 0.35))
            tell(e.getPlayer(), "§eТелепортация отменена: движение.");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (authorizedTeleports.contains(id)) return;
        if (combatBlocked(e.getPlayer())) { e.setCancelled(true); tell(e.getPlayer(), "§cНельзя телепортироваться в бою!"); return; }
        if (travel.state().cancel(id)) tell(e.getPlayer(), "§eПодготовка телепортации отменена.");
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId(); combat.clear(id); travel.state().cancel(id);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (combat.active(id, System.currentTimeMillis()))
            plugin.getLogger().warning("COMBAT LOGOUT player=" + id + " name=" + e.getPlayer().getName() + " (logged only; no automatic penalty)");
        // Keep the tag until natural expiry to prevent a quick reconnect from bypassing PvP.
        travel.state().cancel(id);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        long remaining = combat.remainingSeconds(e.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (remaining > 0) tell(e.getPlayer(), "§cPvP-тег ещё действует: §f" + remaining + "§c сек.");
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        Location dest = destination(TravelRegistry.Destination.SPAWN, null);
        if (TravelService.safe(dest)) e.setRespawnLocation(dest);
    }
}
