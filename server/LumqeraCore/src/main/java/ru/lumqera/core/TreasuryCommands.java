package ru.lumqera.core;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Locale;

/** Clan vault is limited to clan members; withdrawing requires the leader or an officer. */
final class TreasuryCommands implements CommandExecutor, TabCompleter {
    private static final String PREFIX = "§5§lLUMQERA §8» §r";
    private final EconomyService economy;
    private final ClansService clans;
    TreasuryCommands(EconomyService economy, ClansService clans) { this.economy = economy; this.clans = clans; }
    private void tell(CommandSender s, String message) { s.sendMessage(PREFIX + message); }
    private Long parse(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,15}")) return null;
        try { return Long.parseLong(raw); } catch (NumberFormatException ignored) { return null; }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { tell(sender, "§cТолько для игроков."); return true; }
        if (!player.hasPermission("lumqera.treasury")) { tell(sender, "§cНет разрешения."); return true; }
        ClanRegistry.Clan clan = clans.of(player.getUniqueId());
        if (clan == null) { tell(sender, "§cСначала вступи в клан."); return true; }
        if (!economy.healthy() || !economy.ensureTreasury(clan.name) || !economy.ensure(player.getUniqueId())) {
            tell(sender, "§cЭкономика недоступна. Сообщи администрации."); return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
            tell(sender, "§dКазна §f" + clan.name + "§d: §f" + economy.treasuryBalance(clan.name) + " §7монет");
            tell(sender, "§7/treasury deposit <сумма>; /treasury withdraw <сумма> (глава/офицер)");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("deposit", "withdraw").contains(action) || args.length != 2) {
            tell(sender, "§cИспользование: /treasury balance|deposit <сумма>|withdraw <сумма>"); return true;
        }
        Long amount = parse(args[1]);
        if (amount == null || amount <= 0 || amount > economy.maxTransfer()) {
            tell(sender, "§cСумма должна быть от 1 до " + economy.maxTransfer()); return true;
        }
        if (action.equals("withdraw") && !clan.isOfficerOrOwner(player.getUniqueId())) {
            tell(sender, "§cСнимать деньги могут только глава или офицер."); return true;
        }
        EconomyRegistry.Code result = action.equals("deposit")
                ? economy.deposit(player.getUniqueId(), clan.name, amount)
                : economy.withdraw(clan.name, player.getUniqueId(), amount);
        switch (result) {
            case OK -> tell(sender, "§aГотово: §f" + amount + " §aмонет. Казна: §f" + economy.treasuryBalance(clan.name));
            case INSUFFICIENT -> tell(sender, "§cНедостаточно монет.");
            case WALLET_LIMIT -> tell(sender, "§cПревышен допустимый баланс получателя.");
            case STORAGE_ERROR -> tell(sender, "§cНе удалось сохранить операцию — перевод отменён.");
            default -> tell(sender, "§cОперация не выполнена: " + result);
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("balance", "deposit", "withdraw").stream()
                .filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
