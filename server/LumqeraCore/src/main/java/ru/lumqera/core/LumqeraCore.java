package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import org.bukkit.projectiles.ProjectileSource;

import java.util.*;

/** LUMQERA v1.0.0-RC9: PvP, clans, GUI shop and trades. */
public final class LumqeraCore extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    private ClansService clans;
    private StatsService stats;
    private PublicStatsExporter exporter;
    private LandService land;
    private TravelService travel;
    private TravelManager travelManager;
    private WarService wars;
    private OutpostService outposts;
    private EconomyService economy;
    private ModerationService moderation;
    private TradeCommands trades;
    private ReportService reports;
    private boolean startupComplete = false;

    @Override
    public void onEnable() {
        startupComplete = false;
        try {
        // Parse all existing YAML strictly BEFORE any module can overwrite broken storage.
        // Bukkit's YamlConfiguration.loadConfiguration(File) can return an empty config
        // on parse failure; using that path for this check would risk data loss.
        List<String> storageErrors = DataFileGuard.inspect(getDataFolder().toPath(), contents -> {
            YamlConfiguration data = new YamlConfiguration();
            data.loadFromString(contents);
        });
        if (!storageErrors.isEmpty()) {
            for (String issue : storageErrors) getLogger().severe("DATA PREFLIGHT: " + issue);
            getLogger().severe("LUMQERA startup ABORTED. Restore YAML from backup before retrying.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            DataFileGuard.snapshot(getDataFolder().toPath());
        } catch (IOException ex) {
            getLogger().severe("Cannot back up data before startup: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        clans = new ClansService(getDataFolder(), getLogger());
        clans.load();
        long antiFarmMs = Math.max(0L, getConfig().getLong("pvp.anti-farm-minutes", 30L)) * 60_000L;
        stats = new StatsService(getDataFolder(), getLogger(), antiFarmMs);
        stats.load();
        land = new LandService(getDataFolder(), getLogger(), clans);
        land.load();
        travel = new TravelService(getDataFolder(), getLogger());
        travel.load();
        travelManager = new TravelManager(this, clans, land, travel);
        wars = new WarService(getDataFolder(), getLogger());
        wars.load(clans);
        outposts = new OutpostService(getDataFolder(), getLogger(), clans, land);
        outposts.load();
        economy = new EconomyService(getDataFolder(), getLogger(),
                getConfig().getLong("economy.max-balance", 1000000000L),
                getConfig().getLong("economy.start-balance", 150L),
                getConfig().getLong("economy.max-transfer", 10000L));
        economy.load();
        if (!economy.healthy()) throw new IllegalStateException("Economy data failed semantic validation");
        EconomyCommands economyCommands = new EconomyCommands(economy, getConfig().getLong("economy.pay-cooldown-seconds", 3));
        for (String action : List.of("balance", "pay", "eco")) {
            PluginCommand moneyCommand = Objects.requireNonNull(getCommand(action), "Missing command " + action);
            moneyCommand.setExecutor(economyCommands);
            moneyCommand.setTabCompleter(economyCommands);
        }
        TreasuryCommands treasuryCommands = new TreasuryCommands(economy, clans);
        PluginCommand treasuryCommand = Objects.requireNonNull(getCommand("treasury"), "Missing treasury command");
        treasuryCommand.setExecutor(treasuryCommands);
        treasuryCommand.setTabCompleter(treasuryCommands);
        ShopCommands shopCommands = new ShopCommands(economy, getConfig().getConfigurationSection("shop.offers"),
                getConfig().getInt("shop.max-bundles", 16), getLogger());
        PluginCommand shopCommand = Objects.requireNonNull(getCommand("shop"), "Missing shop command");
        shopCommand.setExecutor(shopCommands);
        shopCommand.setTabCompleter(shopCommands);
        LumqeraMenus menus = new LumqeraMenus(this, shopCommands, clans, economy);
        PluginCommand menuCommand = Objects.requireNonNull(getCommand("menu"), "Missing menu command");
        menuCommand.setExecutor(menus);
        Bukkit.getPluginManager().registerEvents(menus, this);
        trades = new TradeCommands(this, travelManager);
        PluginCommand tradeCommand = Objects.requireNonNull(getCommand("trade"), "Missing trade command");
        tradeCommand.setExecutor(trades);
        tradeCommand.setTabCompleter(trades);
        Bukkit.getPluginManager().registerEvents(trades, this);
        Bukkit.getScheduler().runTaskTimer(this, trades::tick, 20L, 20L);

        moderation = new ModerationService(getDataFolder(), getLogger(),
                getConfig().getInt("staff.max-warnings-per-player", 50));
        moderation.load();
        if (!moderation.healthy()) throw new IllegalStateException("Warnings data failed semantic validation");
        StaffCommands staffCommands = new StaffCommands(moderation);
        PluginCommand staffCommand = Objects.requireNonNull(getCommand("staff"), "Missing staff command");
        staffCommand.setExecutor(staffCommands);
        staffCommand.setTabCompleter(staffCommands);
        reports = new ReportService(getDataFolder(), getLogger(),
                getConfig().getInt("reports.max-records", 2000),
                getConfig().getLong("reports.cooldown-seconds", 60));
        reports.load();
        if (!reports.healthy()) throw new IllegalStateException("Reports data failed semantic validation");
        ReportCommands reportCommands = new ReportCommands(reports);
        for (String commandName : List.of("report", "reports")) {
            PluginCommand command = Objects.requireNonNull(getCommand(commandName), "Missing command " + commandName);
            command.setExecutor(reportCommands);
            command.setTabCompleter(reportCommands);
        }
        // No economic or travel migrations before critical stores validate successfully.
        for (ClanRegistry.Clan clan : clans.all())
            if (!economy.ensureTreasury(clan.name)) throw new IllegalStateException("Cannot persist treasury for " + clan.name);
        for (Player online : Bukkit.getOnlinePlayers())
            if (!economy.ensure(online.getUniqueId())) throw new IllegalStateException("Cannot persist account for " + online.getName());
        for (ClanRegistry.Clan clan : clans.all()) travel.invalidateHomeIfUnclaimed(clan.name, land);
        exporter = new PublicStatsExporter(clans, stats, land, wars, outposts, getDataFolder().toPath(), getLogger());
        LandCommands landCommands = new LandCommands(clans, land, travel, outposts, getConfig().getInt("land.max-chunks-per-clan", 12), getConfig().getStringList("land.allowed-worlds"), getLogger());
        for (String action : List.of("claim", "unclaim", "claims")) {
            PluginCommand territoryCommand = Objects.requireNonNull(getCommand(action), "Missing command " + action);
            territoryCommand.setExecutor(landCommands);
            territoryCommand.setTabCompleter(landCommands);
        }
        PluginCommand warCommand = Objects.requireNonNull(getCommand("war"), "Missing command war");
        WarsCommand warHandler = new WarsCommand(clans, wars,
                Math.max(10,getConfig().getLong("wars.invite-seconds",120))*1000L,
                Math.max(1,getConfig().getLong("wars.duration-minutes",45))*60_000L,
                Math.max(1,Math.min(1000,getConfig().getInt("wars.kills-to-win",5))));
        warCommand.setExecutor(warHandler); warCommand.setTabCompleter(warHandler);
        PluginCommand outpostCommand = Objects.requireNonNull(getCommand("outpost"), "Missing command outpost");
        OutpostCommand outpostHandler = new OutpostCommand(outposts, getConfig().getStringList("land.allowed-worlds"),
                Math.max(5,Math.min(64,getConfig().getInt("outposts.radius",14))));
        outpostCommand.setExecutor(outpostHandler); outpostCommand.setTabCompleter(outpostHandler);
        for (String action : List.of("spawn", "combat")) {
            PluginCommand travelCommand = Objects.requireNonNull(getCommand(action), "Missing command " + action);
            travelCommand.setExecutor(travelManager);
        }
        for (String commandName : List.of("clan", "pvpstats", "top", "lumqera")) {
            PluginCommand command = Objects.requireNonNull(getCommand(commandName), "Missing command " + commandName);
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new LandProtectionListener(land), this);
        Bukkit.getPluginManager().registerEvents(travelManager, this);
        Bukkit.getScheduler().runTaskTimer(this, travelManager::tick, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now=System.currentTimeMillis();
            for (WarRegistry.Finish finish:wars.state().tick(now)) announceWar(finish);
            // War expiry is rare; save each tick only when a war actually ended.
        }, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, () -> outposts.tick(System.currentTimeMillis(),
                Math.max(1,getConfig().getInt("outposts.min-members",2)),
                Math.max(5,getConfig().getLong("outposts.capture-seconds",45))*1000L,
                Math.max(1,getConfig().getLong("outposts.reward-minutes",10))*60_000L,
                Math.max(0,getConfig().getInt("outposts.influence-per-cycle",5))), 60L, 60L);
        for (Player online : Bukkit.getOnlinePlayers()) clans.remember(online.getUniqueId(), online.getName());
        exporter.export();
        long ticks = Math.max(20L, getConfig().getLong("export.interval-seconds", 60L) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, exporter::export, ticks, ticks);
        startupComplete = true;
        getLogger().info("LUMQERA Core v1.0.0-RC9 started with " + clans.all().size() + " clans and " + land.state().allClaims().size() + " claims");
        } catch (RuntimeException ex) {
            startupComplete = false;
            getLogger().severe("STARTUP FAILED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            getLogger().severe("No game data will be saved by onDisable after a failed startup. Restore or repair the original YAML and retry.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (!startupComplete) {
            getLogger().warning("Skipping saves because LumqeraCore never finished startup; preserving existing YAML files.");
            return;
        }
        startupComplete = false;
        if (clans != null) clans.save();
        if (stats != null) stats.save();
        if (economy != null) economy.save();
        if (land != null) land.save();
        if (travel != null) travel.save();
        if (wars != null) wars.save();
        if (outposts != null) outposts.save();
        if (exporter != null) exporter.export();
    }

    private void send(CommandSender sender, String message) { sender.sendMessage(PREFIX + message); }
    private int maxMembers() { return Math.max(2, getConfig().getInt("clans.max-members", 10)); }
    private String nick(UUID id) { return clans.knownName(id); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "clan" -> clanCommand(sender, args);
            case "pvpstats" -> statsCommand(sender, args);
            case "top" -> topCommand(sender, args);
            case "lumqera" -> adminCommand(sender, args);
            default -> false;
        };
    }

    private boolean clanCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            for (String line : List.of(
                    "§d/clan create <имя> §7— создать клан",
                    "§d/clan invite <ник> §7— приглашение (глава/офицер)",
                    "§d/clan accept §7— вступить по приглашению",
                    "§d/clan info [имя] §7— состав, роль и влияние",
                    "§d/clan list §7— список кланов",
                    "§d/clan leave §7— покинуть клан",
                    "§d/clan kick <ник> §7— исключить участника",
                    "§d/clan promote/demote <ник> §7— сменить роль (глава)",
                    "§d/clan transfer <ник> confirm §7— передать главенство",
                    "§d/clan disband confirm §7— распустить клан",
                    "§d/clan sethome/home/delhome §7— клановая база",
                    "§d/spawn, /combat §7— телепорт и PvP-тег",
                    "§d/war help, /outpost list §7— войны и PvP-точки",
                    "§d/claim, /unclaim, /claims §7— управление чанками",
                    "§d/balance, /pay <ник> <сумма> §7— кошелёк и переводы",
                    "§d/treasury §7— общая казна клана",
                    "§d/shop list §7— магазин предметов")) send(sender, line);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            List<ClanRegistry.Clan> all = new ArrayList<>(clans.all());
            all.sort(Comparator.comparingInt((ClanRegistry.Clan c) -> c.influence).reversed().thenComparing(c -> c.name));
            if (all.isEmpty()) send(sender, "§7Пока нет кланов.");
            else for (int i = 0; i < Math.min(10, all.size()); i++) {
                ClanRegistry.Clan c = all.get(i);
                send(sender, "§d#" + (i + 1) + " §f" + c.name + " §7| Влияние §d" + c.influence + " §7| §f" + c.members.size() + "/" + maxMembers());
            }
            return true;
        }
        if (!(sender instanceof Player player)) { send(sender, "§cКоманда доступна только в игре."); return true; }
        if (!player.hasPermission("lumqera.clan")) { send(sender, "§cНедостаточно прав."); return true; }
        UUID self = player.getUniqueId();
        ClanRegistry.Clan mine = clans.of(self);
        switch (action) {
            case "sethome", "home", "delhome" -> {
                if (args.length != 1) send(player, "§cИспользование: /clan " + action);
                else travelManager.clanCommand(player, mine, action);
            }
            case "create" -> {
                if (args.length != 2 || !ClanRegistry.validName(args[1])) send(player, "§c/clan create <имя: 3–12 латинских символов, цифр или _>");
                else if (mine != null) send(player, "§cДля создания клана сначала покинь текущий.");
                else if (clans.byName(args[1]) != null) send(player, "§cНазвание уже занято.");
                else if (!economy.ensureTreasury(args[1])) send(player, "§cЭкономика недоступна: создание клана временно запрещено.");
                else if (clans.create(args[1], self)) send(player, "§aКлан §f" + args[1] + " §aсоздан!");
                else send(player, "§cНе удалось создать клан.");
            }
            case "invite" -> {
                if (mine == null || !mine.isOfficerOrOwner(self)) { send(player, "§cПриглашения отправляют глава и офицеры."); return true; }
                if (args.length != 2) { send(player, "§c/clan invite <ник>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { send(player, "§cИгрок должен быть онлайн."); return true; }
                if (target.getUniqueId().equals(self)) { send(player, "§cНельзя пригласить себя."); return true; }
                if (clans.of(target.getUniqueId()) != null) { send(player, "§cИгрок уже состоит в клане."); return true; }
                if (mine.members.size() >= maxMembers()) { send(player, "§cКлан заполнен."); return true; }
                long ttl = Math.max(1, getConfig().getLong("clans.invite-timeout-seconds", 300L));
                long delay = Math.max(0, getConfig().getLong("clans.invite-cooldown-seconds", 10L));
                if (!clans.invite(mine, self, target.getUniqueId(), ttl * 1000L, delay * 1000L, System.currentTimeMillis())) {
                    send(player, "§cНе удалось пригласить. Подожди перед следующим приглашением."); return true;
                }
                send(player, "§aИгроку §f" + target.getName() + " §aотправлено приглашение.");
                send(target, "§eПриглашение в клан §f" + mine.name + "§e! §d/clan accept §7(действует " + ttl + " сек.)");
            }
            case "accept" -> {
                if (mine != null) { send(player, "§cТы уже состоишь в клане."); return true; }
                ClanRegistry.Clan joined = clans.accept(self, maxMembers(), System.currentTimeMillis());
                if (joined == null) send(player, "§cПриглашение истекло, отсутствует или клан заполнен.");
                else send(player, "§aТеперь ты участник клана §f" + joined.name);
            }
            case "info" -> {
                ClanRegistry.Clan clan = args.length >= 2 ? clans.byName(args[1]) : mine;
                if (clan == null) { send(player, "§cКлан не найден. /clan list"); return true; }
                send(player, "§d" + clan.name + " §7| §f" + clan.members.size() + "/" + maxMembers() + " игроков §7| Влияние: §d" + clan.influence);
                send(player, "§7Глава: §f" + nick(clan.owner));
                String members = String.join("§7, §f", clan.members.stream().map(id -> {
                    String role = clan.owner.equals(id) ? " §6★" : (clan.officers.contains(id) ? " §d◆" : "");
                    return nick(id) + role;
                }).toList());
                send(player, "§7Состав: §f" + members);
            }
            case "leave" -> {
                if (mine == null) { send(player, "§cТы не состоишь в клане."); return true; }
                if (mine.owner.equals(self)) { send(player, "§cСначала передай главенство или распусти клан."); return true; }
                if (clans.remove(mine, self)) send(player, "§aТы покинул клан.");
            }
            case "kick" -> {
                if (mine == null || !mine.isOfficerOrOwner(self)) { send(player, "§cИсключать могут глава и офицеры."); return true; }
                if (args.length != 2) { send(player, "§c/clan kick <ник>"); return true; }
                UUID target = clans.memberByName(mine, args[1]);
                if (target == null || target.equals(self) || (mine.officers.contains(target) && !mine.owner.equals(self)) || mine.owner.equals(target)) {
                    send(player, "§cИгрок не найден в составе или у тебя недостаточно прав."); return true;
                }
                if (clans.remove(mine, target)) {
                    send(player, "§aИгрок §f" + nick(target) + " §aисключён.");
                    Player online = Bukkit.getPlayer(target);
                    if (online != null) send(online, "§cТебя исключили из клана §f" + mine.name);
                }
            }
            case "promote", "demote" -> {
                if (mine == null || !mine.owner.equals(self)) { send(player, "§cНазначать офицеров может только глава."); return true; }
                if (args.length != 2) { send(player, "§c/clan " + action + " <ник>"); return true; }
                UUID target = clans.memberByName(mine, args[1]);
                boolean success = target != null && (action.equals("promote") ? clans.promote(mine, self, target) : clans.demote(mine, self, target));
                send(player, success ? "§aРоль игрока §f" + nick(target) + " §aизменена." : "§cНе удалось изменить роль.");
            }
            case "transfer" -> {
                if (mine == null || !mine.owner.equals(self)) { send(player, "§cТолько глава может передать клан."); return true; }
                if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) { send(player, "§cПодтверди: /clan transfer <ник> confirm"); return true; }
                UUID target = clans.memberByName(mine, args[1]);
                if (target != null && clans.transfer(mine, self, target)) send(player, "§eНовый глава клана: §f" + nick(target));
                else send(player, "§cЦель не найдена среди участников.");
            }
            case "disband" -> {
                if (mine == null || !mine.owner.equals(self)) { send(player, "§cРаспускать клан может только глава."); return true; }
                if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) { send(player, "§cПодтверди: /clan disband confirm"); return true; }
                String name = mine.name;
                if (economy.treasuryBalance(name) > 0) {
                    send(player, "§cВ казне остались монеты. Сначала выведи всё через /treasury withdraw <сумма>.");
                    return true;
                }
                EconomyRegistry.Code closed = economy.closeEmptyTreasury(name);
                if (closed != EconomyRegistry.Code.OK) {
                    send(player, "§cНе удалось закрыть казну: " + closed + ". Распуск клана отменён.");
                    return true;
                }
                if (clans.disband(mine)) {
                    WarRegistry.Finish forfeit = wars.state().abandon(name);
                    if (forfeit != null) announceWar(forfeit);
                    wars.state().eraseClan(name);
                    wars.save();
                    outposts.state().eraseClan(name);
                    outposts.save();
                    int removed = land.removeClan(name);
                    if (travel.state().deleteHome(name)) travel.save();
                    getLogger().info("CLAN DISBAND actor=" + self + " clan=" + name + " removedClaims=" + removed);
                    send(player, "§eКлан §f" + name + " §eраспущен; удалено территорий: §f" + removed);
                }
            }
            default -> send(player, "§cНеизвестная команда. /clan help");
        }
        return true;
    }

    private UUID findKnownPlayer(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (UUID id : stats.state().all().keySet()) if (nick(id).equalsIgnoreCase(name)) return id;
        for (ClanRegistry.Clan clan : clans.all()) {
            UUID id = clans.memberByName(clan, name);
            if (id != null) return id;
        }
        return null;
    }

    private boolean statsCommand(CommandSender sender, String[] args) {
        UUID id;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { send(sender, "§cИспользование: /pvpstats <ник>"); return true; }
            id = player.getUniqueId();
        } else id = findKnownPlayer(args[0]);
        if (id == null) { send(sender, "§cИгрок ещё не появлялся на сервере или нет статистики."); return true; }
        CombatRegistry.Stats data = stats.of(id);
        send(sender, "§dPvP §f" + nick(id) + " §7| Убийств: §a" + data.kills + " §7| Смертей: §c" + data.deaths + " §7| K/D: §f" + String.format(Locale.ROOT, "%.2f", data.kd()));
        return true;
    }

    private boolean topCommand(CommandSender sender, String[] args) {
        String type = args.length == 0 ? "clans" : args[0].toLowerCase(Locale.ROOT);
        if (type.equals("clans")) {
            List<ClanRegistry.Clan> entries = new ArrayList<>(clans.all());
            entries.sort(Comparator.comparingInt((ClanRegistry.Clan c) -> c.influence).reversed().thenComparing(c -> c.name));
            send(sender, "§dТоп кланов по влиянию:");
            if (entries.isEmpty()) send(sender, "§7Пока нет кланов.");
            for (int i = 0; i < Math.min(10, entries.size()); i++) send(sender, "§f" + (i + 1) + ". §d" + entries.get(i).name + " §7— §f" + entries.get(i).influence);
        } else if (type.equals("pvp")) {
            List<Map.Entry<UUID, CombatRegistry.Stats>> entries = new ArrayList<>(stats.state().all().entrySet());
            entries.sort(Comparator.<Map.Entry<UUID, CombatRegistry.Stats>>comparingInt(e -> e.getValue().kills).reversed());
            send(sender, "§dТоп PvP по убийствам:");
            if (entries.isEmpty()) send(sender, "§7Статистики пока нет.");
            for (int i = 0; i < Math.min(10, entries.size()); i++) {
                var entry = entries.get(i);
                send(sender, "§f" + (i + 1) + ". §d" + nick(entry.getKey()) + " §7— §f" + entry.getValue().kills + " убийств");
            }
        } else send(sender, "§cИспользование: /top clans или /top pvp");
        return true;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            send(sender, "§dLumqeraCore §fv1.0.0-RC9 §7| Кланов: §f" + clans.all().size() + " §7| Чанков: §f" + land.state().allClaims().size());
            send(sender, "§7Игроки: §f/claim, /unclaim, /claims, /top clans, /top pvp, /balance, /pay");
            if (sender.hasPermission("lumqera.admin")) {
                send(sender, "§d/lumqera status §7— диагностика состояния игрового ядра");
                send(sender, "§d/lumqera export §7— обновить рейтинг");
                send(sender, "§d/eco give|take|set <ник> <сумма> §7— управление экономикой (отдельное право)");
                send(sender, "§d/outpost set <имя> §7— поставить PvP-точку (не на чужих территориях)");
                send(sender, "§d/outpost remove <имя> §7— удалить PvP-точку");
                send(sender, "§d/lumqera setspawn §7— запомнить спавн в защищённой зоне");
                send(sender, "§d/lumqera spawninfo §7— проверить точку спавна");
                send(sender, "§d/lumqera zone set <имя> <радиус> §7— квадратная safe-зона от твоих координат");
                send(sender, "§d/lumqera zone remove <имя> §7— убрать зону");
                send(sender, "§d/lumqera zone list §7— список зон");
            }
            return true;
        }
        if (!sender.hasPermission("lumqera.admin")) { send(sender, "§cНедостаточно прав."); return true; }
        if (args[0].equalsIgnoreCase("status")) {
            send(sender, "§dLUMQERA §fv1.0.0-RC9 §7| Paper: §f" + Bukkit.getBukkitVersion());
            send(sender, "§7Онлайн: §f" + Bukkit.getOnlinePlayers().size() + "§7 | Кланы: §f" + clans.all().size()
                    + "§7 | Чанки: §f" + land.state().allClaims().size()
                    + "§7 | Аванпосты: §f" + outposts.state().all().size());
            send(sender, economy.healthy() ? "§7Экономика: §aдоступна" : "§7Экономика: §cЗАБЛОКИРОВАНА — проверьте логи");
            send(sender, reports.healthy() ? "§7Очередь жалоб: §f" + reports.openCount() + " открытых" :
                    "§7Жалобы: §cЗАБЛОКИРОВАНЫ — проверьте reports.yml");
            return true;
        }
        if (args[0].equalsIgnoreCase("setspawn")) { travelManager.adminSetSpawn(sender); return true; }
        if (args[0].equalsIgnoreCase("spawninfo")) { travelManager.spawnInfo(sender); return true; }
        if (args[0].equalsIgnoreCase("export")) {
            send(sender, exporter.export() ? "§aСтатистика экспортирована." : "§cОшибка экспорта — см. консоль.");
            return true;
        }
        if (!args[0].equalsIgnoreCase("zone") || args.length < 2) { send(sender, "§cИспользуй /lumqera help"); return true; }
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("list")) {
            if (land.state().zones().isEmpty()) { send(sender, "§7Безопасные зоны пока не заданы."); return true; }
            for (LandRegistry.Zone zone : land.state().zones())
                send(sender, "§d" + zone.name() + " §7| §f" + zone.radius() + " блоков §7| Мир: §f" + zone.world().toString().substring(0, 8) + " §7| §f" + zone.centerX() + ", " + zone.centerZ());
            return true;
        }
        if (operation.equals("remove") && args.length == 3) {
            if (land.removeZone(args[2])) {
                getLogger().info("ZONE REMOVE actor=" + sender.getName() + " name=" + args[2]);
                send(sender, "§aЗона удалена.");
            } else send(sender, "§cЗона не найдена.");
            return true;
        }
        if (operation.equals("set") && args.length == 4) {
            if (!(sender instanceof Player p)) { send(sender, "§cУстановка зоны выполняется только игроком на месте."); return true; }
            try {
                int radius = Integer.parseInt(args[3]);
                if (outposts.state().all().stream().anyMatch(point -> point.world.equals(p.getWorld().getUID())
                        && Math.abs((long)point.x-p.getLocation().getBlockX())<=((long)radius+point.radius)
                        && Math.abs((long)point.z-p.getLocation().getBlockZ())<=((long)radius+point.radius))) {
                    send(sender,"§cБезопасная зона не может перекрывать PvP-точку. Сначала удалите точку."); return true;
                }
                if (!land.setZone(args[2], p.getLocation(), radius)) { send(sender, "§cИмя: 3–24 латинских символов. Радиус: от 8 до 1024."); return true; }
                getLogger().info("ZONE SET actor=" + p.getUniqueId() + " name=" + args[2] + " radius=" + radius + " at=" + p.getLocation().getBlockX() + "," + p.getLocation().getBlockZ());
                send(sender, "§aЗона §f" + args[2] + " §aустановлена. Радиус §f" + radius + "§a блоков (квадрат по X/Z, на всю высоту).");
                send(sender, "§eЕсли здесь уже есть чанки кланов, безопасная зона имеет приоритет.");
            } catch (NumberFormatException ex) { send(sender, "§cРадиус должен быть целым числом от 8 до 1024."); }
            return true;
        }
        send(sender, "§cИспользование: /lumqera zone set|remove|list");
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clans.remember(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        if (!economy.ensure(event.getPlayer().getUniqueId()))
            getLogger().severe("Could not initialize wallet for " + event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (getConfig().getBoolean("clans.friendly-fire", false) || !(event.getEntity() instanceof Player defender)) return;
        Entity attacker = event.getDamager();
        Player source = null;
        if (attacker instanceof Player player) source = player;
        else if (attacker instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) source = player;
        }
        if (source != null && clans.sameClan(source.getUniqueId(), defender.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        UUID killerId = killer == null ? null : killer.getUniqueId();
        boolean allied = killerId != null && clans.sameClan(victim.getUniqueId(), killerId);
        CombatRegistry.Result result = stats.recordDeath(victim.getUniqueId(), killerId, allied);
        if (result == CombatRegistry.Result.CREDITED) {
            if (killerId != null) {
                ClanRegistry.Clan attackerClan=clans.of(killerId);
                ClanRegistry.Clan defenderClan=clans.of(victim.getUniqueId());
                if (attackerClan != null && defenderClan != null) {
                    WarRegistry.Hit warHit=wars.state().creditedKill(attackerClan.name, defenderClan.name, System.currentTimeMillis());
                    if (warHit.counted()) {
                        wars.save();
                        if (warHit.ended()!=null) announceWar(warHit.ended());
                        else Bukkit.broadcastMessage(PREFIX+"§dВойна: §f"+attackerClan.name+" §dполучил очко за убийство " + defenderClan.name);
                    }
                }
            }
            int points = Math.max(0, getConfig().getInt("pvp.clan-influence-per-kill", 3));
            if (points > 0) clans.reward(killerId, points);
            long coins = Math.max(0L, getConfig().getLong("economy.coins-per-credited-kill", 20L));
            if (killerId != null && coins > 0 && economy.ensure(killerId)) {
                EconomyRegistry.Code earned = economy.reward(killerId, coins);
                if (earned == EconomyRegistry.Code.OK) send(killer, "§6+" + coins + " монет §7за PvP-победу.");
                else getLogger().warning("PVP reward skipped (" + earned + ") for " + killerId);
            }
            if (killer != null) send(killer, "§aПобеда засчитана! §7+" + points + " влияния клану (если в клане).");
        } else if (result == CombatRegistry.Result.FARM_BLOCKED && killer != null) {
            send(killer, "§eПовторное убийство этого игрока не учитывается в рейтинге (антифарм).");
        } else if (result == CombatRegistry.Result.PERSISTENCE_FAILED && killer != null) {
            send(killer, "§cСтатистика PvP не сохранилась. Награда не начислена; сообщите администрации.");
        }
    }

    private void announceWar(WarRegistry.Finish finish) {
        if (finish==null) return;
        wars.save();
        if (finish.draw()) Bukkit.broadcastMessage(PREFIX+"§eКлановая война завершилась ничьей.");
        else Bukkit.broadcastMessage(PREFIX+"§aПобеда клана §f"+finish.winner()+"§a над §f"+finish.loser()+"§a! Причина: §f"+finish.reason());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("top") && args.length == 1) return filter(List.of("clans", "pvp"), args[0]);
        if (name.equals("lumqera") && args.length == 1) return filter(sender.hasPermission("lumqera.admin") ? List.of("help", "status", "export", "zone", "setspawn", "spawninfo") : List.of("help"), args[0]);
        if (name.equals("lumqera") && args.length == 2 && args[0].equalsIgnoreCase("zone") && sender.hasPermission("lumqera.admin")) return filter(List.of("set", "remove", "list"), args[1]);
        if (name.equals("lumqera") && args.length == 3 && args[0].equalsIgnoreCase("zone") && args[1].equalsIgnoreCase("remove") && sender.hasPermission("lumqera.admin")) return filter(land.state().zones().stream().map(LandRegistry.Zone::name).toList(), args[2]);
        if (!name.equals("clan")) return List.of();
        if (args.length == 1) return filter(List.of("help", "create", "invite", "accept", "info", "list", "leave", "kick", "promote", "demote", "transfer", "disband", "sethome", "home", "delhome"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("disband")) return filter(List.of("confirm"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("transfer")) return filter(List.of("confirm"), args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) return filter(clans.all().stream().map(c -> c.name).toList(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        if (args.length == 2 && List.of("kick", "promote", "demote", "transfer").contains(args[0].toLowerCase(Locale.ROOT)) && sender instanceof Player player) {
            ClanRegistry.Clan clan = clans.of(player.getUniqueId());
            return clan == null ? List.of() : filter(clan.members.stream().map(this::nick).toList(), args[1]);
        }
        return List.of();
    }
    private List<String> filter(Collection<String> choices, String prefix) {
        String start = prefix.toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(start)).limit(25).toList();
    }
}
