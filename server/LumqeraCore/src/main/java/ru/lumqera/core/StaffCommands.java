package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Permission-gated moderation: online warnings, history via UUID, and deletion with audit log. */
final class StaffCommands implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    private final ModerationService moderation;
    StaffCommands(ModerationService moderation) { this.moderation = moderation; }
    private void tell(CommandSender s, String msg) { s.sendMessage(PREFIX + msg); }
    private UUID resolve(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return online.getUniqueId();
        try { return UUID.fromString(input); }
        catch (IllegalArgumentException ex) { return null; }
    }
    @Override public boolean onCommand(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("lumqera.staff.warn")) { tell(sender, "§cНедостаточно прав."); return true; }
        if (!moderation.healthy()) { tell(sender, "§cХранилище предупреждений повреждено. Обратитесь к администратору сервера."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            tell(sender, "§d/staff warn <онлайн-игрок> <причина>");
            tell(sender, "§d/staff history <ник онлайн | UUID> §7— последние предупреждения");
            tell(sender, "§d/staff remove <ID> §7— удалить запись (с аудитом в консоли)");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "warn" -> {
                if (args.length < 3) { tell(sender, "§c/staff warn <онлайн-игрок> <причина>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { tell(sender, "§cИгрок должен быть онлайн."); return true; }
                String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                try {
                    WarningRegistry.Warning warning = moderation.warn(target.getUniqueId(),
                            sender instanceof Player p ? p.getName() : "CONSOLE", reason, System.currentTimeMillis());
                    if (warning == null) { tell(sender, "§cНе удалось сохранить предупреждение."); return true; }
                    tell(sender, "§aПредупреждение #" + warning.id() + " для " + target.getName() + " сохранено.");
                    tell(target, "§cПредупреждение администрации #" + warning.id() + ": §f" + warning.reason());
                    Bukkit.getLogger().info("[LUMQERA STAFF] warn id=" + warning.id() + " actor=" + sender.getName() + " target=" + target.getUniqueId());
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    tell(sender, "§cПричина 3–160 символов, без управляющих знаков; не более установленного лимита предупреждений.");
                }
                return true;
            }
            case "history" -> {
                if (args.length != 2) { tell(sender, "§c/staff history <ник онлайн | UUID>"); return true; }
                UUID player = resolve(args[1]);
                if (player == null) { tell(sender, "§cИгрок не найден онлайн; для офлайн-игрока используй UUID."); return true; }
                List<WarningRegistry.Warning> warnings = moderation.history(player);
                tell(sender, "§dПредупреждения " + player + ": §f" + warnings.size());
                for (int i = Math.max(0, warnings.size() - 10); i < warnings.size(); i++) {
                    var w = warnings.get(i);
                    tell(sender, "§7#" + w.id() + " §f" + w.reason() + " §8(" + w.moderator() + ")");
                }
                return true;
            }
            case "remove" -> {
                if (args.length != 2 || !args[1].matches("[0-9]{1,18}")) { tell(sender, "§c/staff remove <ID>"); return true; }
                long id;
                try { id = Long.parseLong(args[1]); }
                catch (NumberFormatException ex) { tell(sender, "§cНеверный ID."); return true; }
                WarningRegistry.Warning existing = moderation.find(id);
                if (existing == null || !moderation.remove(id)) { tell(sender, "§cПредупреждение не найдено или сохранение не удалось."); return true; }
                Bukkit.getLogger().info("[LUMQERA STAFF] remove-warning id=" + id + " actor=" + sender.getName() + " target=" + existing.player());
                tell(sender, "§aПредупреждение #" + id + " удалено.");
                return true;
            }
            default -> { tell(sender, "§cНеизвестная команда. /staff help"); return true; }
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("lumqera.staff.warn")) return List.of();
        if (args.length == 1) return List.of("warn", "history", "remove", "help").stream()
                .filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && (args[0].equalsIgnoreCase("warn") || args[0].equalsIgnoreCase("history")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(x -> x.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
