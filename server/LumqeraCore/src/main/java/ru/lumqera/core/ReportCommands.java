package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.time.Instant;
import java.util.*;

/** /report is player-only, /reports is staff-only. Never export reports to the website. */
final class ReportCommands implements CommandExecutor, TabCompleter {
    private final ReportService service;
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    ReportCommands(ReportService service) { this.service = service; }
    private void tell(CommandSender sender, String text) { sender.sendMessage(PREFIX + text); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean staff = command.getName().equalsIgnoreCase("reports");
        if (!service.healthy()) { tell(sender, "§cСистема обращений временно недоступна. Сообщите администратору."); return true; }
        if (!staff) {
            if (!(sender instanceof Player author)) { tell(sender, "§cКоманда доступна только игрокам."); return true; }
            if (!author.hasPermission("lumqera.report")) { tell(sender, "§cНет разрешения."); return true; }
            if (args.length < 2) { tell(sender, "§e/report <ник онлайн> <причина от 8 до 180 символов>"); return true; }
            Player suspect = Bukkit.getPlayerExact(args[0]);
            if (suspect == null) { tell(sender, "§cИгрок должен быть онлайн."); return true; }
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            ReportRegistry.Result result = service.create(author.getUniqueId(), suspect.getUniqueId(), reason, System.currentTimeMillis());
            switch (result.code()) {
                case OK -> tell(sender, "§aОбращение #" + result.ticket().id() + " зарегистрировано. Спасибо за сообщение.");
                case SELF -> tell(sender, "§cНельзя отправить жалобу на себя.");
                case COOLDOWN -> tell(sender, "§cПодожди перед отправкой следующего обращения.");
                case DUPLICATE -> tell(sender, "§cОбращение на этого игрока уже ожидает рассмотрения.");
                case CAPACITY -> tell(sender, "§cОчередь переполнена. Обратись к администрации позднее.");
                default -> tell(sender, "§cНе удалось сохранить жалобу. Проверь причину (8–180 символов).");
            }
            return true;
        }
        if (!sender.hasPermission("lumqera.staff.reports")) { tell(sender, "§cНедостаточно прав."); return true; }
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            tell(sender, "§dОткрытые обращения: §f" + service.openCount() + " §7(последние 10)");
            for (var t : service.latestOpen(10)) {
                String handler = service.review(t.id()).assignedTo();
                tell(sender, "§7#" + t.id() + " §f" + t.reporter() + " §7→ §f" + t.target() +
                        " §7: " + t.reason() + (handler.isEmpty() ? " §e[свободно]" : " §d[" + handler + "]"));
            }
            return true;
        }
        if (!List.of("show", "close", "claim", "release", "note").contains(action) ||
                args.length < 2 || (action.equals("note") ? args.length < 3 : args.length != 2) ||
                !args[1].matches("[1-9][0-9]{0,17}")) {
            tell(sender, "§e/reports list|show|claim|release|close <ID> §7или §e/reports note <ID> <заметка>");
            return true;
        }
        long id;
        try { id = Long.parseLong(args[1]); }
        catch (NumberFormatException ex) { tell(sender, "§cНеверный номер обращения."); return true; }
        ReportRegistry.Ticket ticket = service.byId(id);
        if (ticket == null) { tell(sender, "§cОбращение не найдено."); return true; }
        if (action.equals("show")) {
            tell(sender, "§d#" + ticket.id() + " §7" + (ticket.closed() ? "закрыто" : "открыто"));
            tell(sender, "§7Автор: §f" + ticket.reporter() + " §7Цель: §f" + ticket.target());
            tell(sender, "§7Причина: §f" + ticket.reason());
            tell(sender, "§7Создано: §f" + Instant.ofEpochMilli(ticket.createdAt()));
            ReportRegistry.Review review = service.review(id);
            tell(sender, "§7Ответственный: §f" + (review.assignedTo().isEmpty() ? "не назначен" : review.assignedTo()));
            tell(sender, "§7Внутренние заметки: §f" + review.notes().size());
            for (var note : review.notes())
                tell(sender, "§7[" + Instant.ofEpochMilli(note.createdAt()) + "] §d" + note.author() + "§7: §f" + note.text());
            if (ticket.closed()) tell(sender, "§7Закрыл: §f" + ticket.closedBy());
            return true;
        }
        String moderator = sender instanceof Player p ? p.getName() : "CONSOLE";
        ReportRegistry.Result result = switch (action) {
            case "claim" -> service.claim(id, moderator, System.currentTimeMillis());
            case "release" -> service.release(id, moderator);
            case "note" -> service.note(id, moderator,
                    String.join(" ", Arrays.copyOfRange(args, 2, args.length)), System.currentTimeMillis());
            default -> service.close(id, moderator, System.currentTimeMillis());
        };
        if (result.code() == ReportRegistry.Code.OK) {
            Bukkit.getLogger().info("[LUMQERA REPORT] action=" + action + " id=" + id + " actor=" + moderator);
            tell(sender, "§aОбращение #" + id + ": действие '" + action + "' выполнено.");
        } else {
            tell(sender, "§cДействие не выполнено: " + switch (result.code()) {
                case CLAIMED -> "обращение уже закреплено за модератором";
                case NOT_OWNER -> "обращение закреплено за другим модератором";
                case NOTE_LIMIT -> "достигнут лимит заметок (20)";
                case NOT_CLAIMED -> "обращение не закреплено";
                case ALREADY_CLOSED -> "обращение уже закрыто";
                default -> "неверные данные или ошибка сохранения";
            });
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("reports")) {
            if (!sender.hasPermission("lumqera.staff.reports")) return List.of();
            if (args.length == 1) return List.of("list", "show", "claim", "release", "note", "close").stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
            return List.of();
        }
        if (args.length == 1 && sender.hasPermission("lumqera.report"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
