package ru.lumqera.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.function.Supplier;

/** Persistent wallet mutations with rollback when a snapshot cannot be saved. */
final class EconomyService {
    private final Path ledgerFile, auditFile;
    private final Logger logger;
    private final EconomyRegistry ledger;
    private final long startAmount, transferLimit;
    private boolean healthy = true;

    EconomyService(File pluginFolder, Logger logger, long maxBalance, long startAmount, long transferLimit) {
        this.ledgerFile = new File(pluginFolder, "economy.yml").toPath();
        this.auditFile = new File(pluginFolder, "economy-audit.log").toPath();
        this.logger = logger;
        this.ledger = new EconomyRegistry(Math.max(1, maxBalance));
        this.startAmount = Math.max(0, Math.min(startAmount, ledger.limit()));
        this.transferLimit = Math.max(1, Math.min(transferLimit, ledger.limit()));
    }
    EconomyRegistry state() { return ledger; }
    long balance(UUID id) { return ledger.balance(id); }
    long maxTransfer() { return transferLimit; }
    boolean healthy() { return healthy; }
    long treasuryBalance(String clan) { return ledger.treasuryBalance(clan); }

    void load() {
        if (!Files.isRegularFile(ledgerFile)) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(ledgerFile.toFile());
        ConfigurationSection section = yaml.getConfigurationSection("accounts");
        int version = yaml.getInt("schema-version", -1);
        if (version != 1 && version != 2) {
            healthy = false;
            logger.severe("economy.yml has an invalid schema. Economy locked; restore from backup.");
            return;
        }
        // v1 (wallets only) remains readable; v2 adds clan treasuries in the same atomic file.
        var accepted = new java.util.HashMap<UUID, Long>();
        for (String raw : section == null ? java.util.Set.<String>of() : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(raw);
                // getLong silently truncates decimals; reject non-integral and corrupted values.
                Object value = yaml.get("accounts." + raw);
                if (!(value instanceof Number n)) throw new IllegalArgumentException("not numeric");
                long amount = n.longValue();
                if (n.doubleValue() != (double) amount || amount < 0 || amount > ledger.limit())
                    throw new IllegalArgumentException("out of bounds");
                accepted.put(id, amount);
            } catch (IllegalArgumentException ex) {
                healthy = false;
                logger.severe("Invalid account in economy.yml: " + raw + ". Economy locked; restore from backup.");
                return;
            }
        }
        var vaults = new java.util.HashMap<String, Long>();
        ConfigurationSection treasurySection = yaml.getConfigurationSection("treasuries");
        if (version == 2 && treasurySection != null) for (String raw : treasurySection.getKeys(false)) {
            try {
                String key = EconomyRegistry.key(raw);
                Object value = yaml.get("treasuries." + raw);
                if (!(value instanceof Number n)) throw new IllegalArgumentException("not numeric");
                long amount = n.longValue();
                if (n.doubleValue() != (double) amount || amount < 0 || amount > ledger.limit()
                        || vaults.putIfAbsent(key, amount) != null)
                    throw new IllegalArgumentException("invalid amount or duplicate treasury");
            } catch (IllegalArgumentException ex) {
                healthy = false;
                logger.severe("Invalid treasury in economy.yml: " + raw + ". Economy locked; restore from backup.");
                return;
            }
        }
        ledger.replaceAll(accepted, vaults);
    }
    boolean save() {
        if (!healthy) return false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 2);
        for (var e : ledger.snapshot().entrySet()) yaml.set("accounts." + e.getKey(), e.getValue());
        for (var e : ledger.treasurySnapshot().entrySet()) yaml.set("treasuries." + e.getKey(), e.getValue());
        return YamlStore.save(yaml, ledgerFile, logger);
    }
    private EconomyRegistry.Code commit(String action, UUID actor, UUID target, long amount, Supplier<EconomyRegistry.Code> change) {
        if (!healthy) return EconomyRegistry.Code.STORAGE_ERROR;
        Map<UUID, Long> before = ledger.snapshot();
        Map<String, Long> beforeVaults = ledger.treasurySnapshot();
        EconomyRegistry.Code result = change.get();
        if (result != EconomyRegistry.Code.OK) return result;
        if (!save()) {
            ledger.replaceAll(before, beforeVaults);
            logger.severe("Economy transaction rolled back because economy.yml could not be saved");
            return EconomyRegistry.Code.STORAGE_ERROR;
        }
        // Audit is append-only, a failure is logged; wallets were already durably saved.
        try {
            Files.createDirectories(auditFile.getParent());
            String row = Instant.now() + " action=" + action + " actor=" + actor + " target=" + target
                    + " amount=" + amount + " senderBalance=" + (actor == null ? "-" : ledger.balance(actor))
                    + " targetBalance=" + (target == null ? "-" : ledger.balance(target)) + System.lineSeparator();
            Files.writeString(auditFile, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { logger.severe("Economy audit append failed: " + e.getMessage()); }
        return EconomyRegistry.Code.OK;
    }
    boolean ensure(UUID player) {
        if (!healthy) return false;
        if (ledger.exists(player)) return true;
        return commit("open", player, player, startAmount, () -> ledger.open(player, startAmount)) == EconomyRegistry.Code.OK;
    }
    boolean ensureTreasury(String clan) {
        if (!healthy) return false;
        if (ledger.treasuryExists(clan)) return true;
        return commit("treasury-open-" + EconomyRegistry.key(clan), null, null, 0L,
                () -> ledger.openTreasury(clan)) == EconomyRegistry.Code.OK;
    }
    EconomyRegistry.Code closeEmptyTreasury(String clan) {
        return commit("treasury-close-" + EconomyRegistry.key(clan), null, null, 0L,
                () -> ledger.closeTreasury(clan));
    }
    EconomyRegistry.Code deposit(UUID from, String clan, long amount) {
        if (amount <= 0 || amount > transferLimit) return EconomyRegistry.Code.BAD_AMOUNT;
        return commit("treasury-deposit-" + EconomyRegistry.key(clan), from, null, amount,
                () -> ledger.deposit(from, clan, amount));
    }
    EconomyRegistry.Code withdraw(String clan, UUID to, long amount) {
        if (amount <= 0 || amount > transferLimit) return EconomyRegistry.Code.BAD_AMOUNT;
        return commit("treasury-withdraw-" + EconomyRegistry.key(clan), null, to, amount,
                () -> ledger.withdraw(clan, to, amount));
    }
    EconomyRegistry.Code purchase(UUID buyer, String offerId, long amount) {
        return commit("shop-buy-" + offerId, buyer, null, amount, () -> ledger.debit(buyer, amount));
    }
    EconomyRegistry.Code refundPurchase(UUID buyer, String offerId, long amount) {
        return commit("shop-refund-" + offerId, null, buyer, amount, () -> ledger.credit(buyer, amount));
    }
    EconomyRegistry.Code transfer(UUID from, UUID to, long amount) {
        if (amount <= 0 || amount > transferLimit) return EconomyRegistry.Code.BAD_AMOUNT;
        return commit("pay", from, to, amount, () -> ledger.transfer(from, to, amount));
    }
    EconomyRegistry.Code reward(UUID player, long amount) {
        return commit("pvp-reward", null, player, amount, () -> ledger.credit(player, amount));
    }
    EconomyRegistry.Code change(UUID target, long amount, String operation, String actor) {
        // actor only comes from server-validated player or console name; never read from chat input.
        String safeActor = actor.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return switch (operation) {
            case "give" -> commit("admin-give-" + safeActor, null, target, amount, () -> ledger.credit(target, amount));
            case "take" -> commit("admin-take-" + safeActor, target, null, amount, () -> ledger.debit(target, amount));
            case "set" -> commit("admin-set-" + safeActor, null, target, amount, () -> ledger.set(target, amount));
            default -> EconomyRegistry.Code.BAD_AMOUNT;
        };
    }
}
