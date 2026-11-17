package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Locale;

/** Player transfers and permission-restricted money administration. */
final class EconomyCommands implements CommandExecutor, TabCompleter {
    private final EconomyService economy;
    private final java.util.Map<java.util.UUID, Long> lastPay = new java.util.HashMap<>();
    private final long cooldownMs;
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    EconomyCommands(EconomyService economy, long cooldownSeconds) {
        this.economy = economy;
        this.cooldownMs = Math.max(0, cooldownSeconds) * 1000;
    }
    private void send(CommandSender sender, String msg) { sender.sendMessage(PREFIX + msg); }
    private Player online(String name) { return Bukkit.getPlayerExact(name); }
    private Long parseAmount(String value) {
        try {
            if (!value.matches("[0-9]{1,15}")) return null;
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) { return null; }
    }
    private String explain(EconomyRegistry.Code code) {
        return switch (code) {
            case OK -> "§aГотово.";
            case BAD_AMOUNT -> "§cНекорректная сумма или превышен лимит операции.";
            case UNKNOWN_ACCOUNT -> "§cСчёт не найден или недоступен для записи.";
            case INSUFFICIENT -> "§cНедостаточно монет.";
            case WALLET_LIMIT -> "§cПревышен максимальный баланс получателя.";
            case SAME_ACCOUNT -> "§cНельзя отправлять монеты самому себе.";
            case UNKNOWN_TREASURY -> "§cКазна клана не найдена.";
            case TREASURY_NOT_EMPTY -> "§cСначала выведи все монеты из казны.";
            case STORAGE_ERROR -> "§cОшибка сохранения кошелька. Операция отменена.";
        };
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> {
                if (!(sender instanceof Player p)) { send(sender, "§cТолько для игрока. Для управления счетами: /eco."); return true; }
                if (!economy.ensure(p.getUniqueId())) { send(sender, "§cСчёт временно недоступен."); return true; }
                send(sender, "§dБаланс: §f" + economy.balance(p.getUniqueId()) + " §7монет");
                return true;
            }
            case "pay" -> {
                if (!(sender instanceof Player p)) { send(sender, "§cКоманда доступна только игрокам."); return true; }
                if (args.length != 2) { send(sender, "§7Использование: §d/pay <онлайн-ник> <сумма>"); return true; }
                Player recipient = online(args[0]);
                if (recipient == null) { send(sender, "§cИгрок должен быть онлайн."); return true; }
                Long amount = parseAmount(args[1]);
                if (amount == null || amount <= 0 || amount > economy.maxTransfer()) { send(sender, "§cСумма должна быть от 1 до " + economy.maxTransfer()); return true; }
                long now = System.currentTimeMillis();
                Long last = lastPay.get(p.getUniqueId());
                if (last != null && now >= last && now - last < cooldownMs) { send(sender, "§eПеред следующей оплатой подожди несколько секунд."); return true; }
                if (!economy.ensure(p.getUniqueId()) || !economy.ensure(recipient.getUniqueId())) { send(sender, "§cСчёт недоступен."); return true; }
                EconomyRegistry.Code result = economy.transfer(p.getUniqueId(), recipient.getUniqueId(), amount);
                if (result != EconomyRegistry.Code.OK) { send(sender, explain(result)); return true; }
                lastPay.put(p.getUniqueId(), now);
                send(sender, "§aПереведено §f" + amount + " §aмонет игроку §f" + recipient.getName() + "§a.");
                send(recipient, "§aПолучено §f" + amount + " §aмонет от §f" + p.getName() + "§a.");
                return true;
            }
            case "eco" -> {
                if (!sender.hasPermission("lumqera.economy.admin")) { send(sender, "§cНедостаточно прав."); return true; }
                if (args.length == 2 && args[0].equalsIgnoreCase("balance")) {
                    Player recipient = online(args[1]);
                    if (recipient == null) { send(sender, "§cИгрок должен быть онлайн."); return true; }
                    send(sender, "§d" + recipient.getName() + ": §f" + economy.balance(recipient.getUniqueId()) + " монет");
                    return true;
                }
                if (args.length != 3 || !List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
                    send(sender, "§7/eco give|take|set <онлайн-ник> <сумма>, /eco balance <ник>"); return true;
                }
                Player recipient = online(args[1]);
                Long amount = parseAmount(args[2]);
                if (recipient == null || amount == null) { send(sender, "§cУкажи онлайн-игрока и корректную целую сумму."); return true; }
                if (!economy.ensure(recipient.getUniqueId())) { send(sender, "§cСчёт недоступен."); return true; }
                EconomyRegistry.Code result = economy.change(recipient.getUniqueId(), amount, args[0].toLowerCase(Locale.ROOT), sender.getName());
                send(sender, explain(result));
                if (result == EconomyRegistry.Code.OK) send(sender, "§7Новый баланс: §f" + economy.balance(recipient.getUniqueId()));
                return true;
            }
        }
        return false;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("eco") && !sender.hasPermission("lumqera.economy.admin")) return List.of();
        if (name.equals("eco") && args.length == 1) return List.of("balance", "give", "take", "set").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 1 && name.equals("pay") || args.length == 2 && name.equals("eco")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(25).toList();
        }
        return List.of();
    }
}
