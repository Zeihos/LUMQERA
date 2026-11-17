package ru.lumqera.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bukkit-independent, integer-only wallet and clan-treasury ledger. All mutations on main thread. */
final class EconomyRegistry {
    enum Code { OK, BAD_AMOUNT, UNKNOWN_ACCOUNT, UNKNOWN_TREASURY, INSUFFICIENT,
        WALLET_LIMIT, SAME_ACCOUNT, TREASURY_NOT_EMPTY, STORAGE_ERROR }
    private final long walletLimit;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<String, Long> treasuries = new HashMap<>();

    EconomyRegistry(long walletLimit) {
        if (walletLimit < 1) throw new IllegalArgumentException("walletLimit must be positive");
        this.walletLimit = walletLimit;
    }
    long limit() { return walletLimit; }
    long balance(UUID id) { return balances.getOrDefault(id, 0L); }
    boolean exists(UUID id) { return id != null && balances.containsKey(id); }
    Map<UUID, Long> snapshot() { return new HashMap<>(balances); }
    Map<String, Long> treasurySnapshot() { return new HashMap<>(treasuries); }
    static String key(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,12}")) throw new IllegalArgumentException("Invalid clan name");
        return name.toLowerCase(Locale.ROOT);
    }
    boolean treasuryExists(String name) { return treasuries.containsKey(key(name)); }
    long treasuryBalance(String name) { return treasuries.getOrDefault(key(name), 0L); }
    void replace(Map<UUID, Long> entries) { replaceAll(entries, Map.of()); }
    void replaceAll(Map<UUID, Long> entries, Map<String, Long> vaults) {
        Objects.requireNonNull(entries);
        Objects.requireNonNull(vaults);
        for (var e : entries.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() < 0 || e.getValue() > walletLimit)
                throw new IllegalArgumentException("Invalid account entry");
        }
        Map<String, Long> normalized = new HashMap<>();
        for (var e : vaults.entrySet()) {
            String canonical = key(e.getKey());
            if (e.getValue() == null || e.getValue() < 0 || e.getValue() > walletLimit || normalized.containsKey(canonical))
                throw new IllegalArgumentException("Invalid treasury entry");
            normalized.put(canonical, e.getValue());
        }
        balances.clear(); balances.putAll(entries);
        treasuries.clear(); treasuries.putAll(normalized);
    }
    Code open(UUID id, long starter) {
        if (id == null || starter < 0 || starter > walletLimit) return Code.BAD_AMOUNT;
        balances.putIfAbsent(id, starter);
        return Code.OK;
    }
    Code openTreasury(String clanName) {
        final String clan;
        try { clan = key(clanName); } catch (IllegalArgumentException ex) { return Code.BAD_AMOUNT; }
        treasuries.putIfAbsent(clan, 0L);
        return Code.OK;
    }
    Code closeTreasury(String clanName) {
        final String clan;
        try { clan = key(clanName); } catch (IllegalArgumentException ex) { return Code.BAD_AMOUNT; }
        if (!treasuries.containsKey(clan)) return Code.UNKNOWN_TREASURY;
        if (treasuries.get(clan) != 0L) return Code.TREASURY_NOT_EMPTY;
        treasuries.remove(clan);
        return Code.OK;
    }
    Code set(UUID id, long amount) {
        if (id == null || !exists(id)) return Code.UNKNOWN_ACCOUNT;
        if (amount < 0 || amount > walletLimit) return Code.BAD_AMOUNT;
        balances.put(id, amount);
        return Code.OK;
    }
    Code credit(UUID id, long amount) {
        if (amount <= 0) return Code.BAD_AMOUNT;
        if (!exists(id)) return Code.UNKNOWN_ACCOUNT;
        long old = balance(id);
        if (amount > walletLimit - old) return Code.WALLET_LIMIT;
        balances.put(id, old + amount);
        return Code.OK;
    }
    Code debit(UUID id, long amount) {
        if (amount <= 0) return Code.BAD_AMOUNT;
        if (!exists(id)) return Code.UNKNOWN_ACCOUNT;
        long old = balance(id);
        if (old < amount) return Code.INSUFFICIENT;
        balances.put(id, old - amount);
        return Code.OK;
    }
    Code transfer(UUID from, UUID to, long amount) {
        if (from == null || to == null || !exists(from) || !exists(to)) return Code.UNKNOWN_ACCOUNT;
        if (from.equals(to)) return Code.SAME_ACCOUNT;
        if (amount <= 0) return Code.BAD_AMOUNT;
        long source = balance(from), target = balance(to);
        if (source < amount) return Code.INSUFFICIENT;
        if (amount > walletLimit - target) return Code.WALLET_LIMIT;
        balances.put(from, source - amount);
        balances.put(to, target + amount);
        return Code.OK;
    }
    Code deposit(UUID from, String clanName, long amount) {
        final String clan;
        try { clan = key(clanName); } catch (IllegalArgumentException ex) { return Code.BAD_AMOUNT; }
        if (!exists(from)) return Code.UNKNOWN_ACCOUNT;
        if (!treasuries.containsKey(clan)) return Code.UNKNOWN_TREASURY;
        if (amount <= 0) return Code.BAD_AMOUNT;
        long source = balance(from), target = treasuries.get(clan);
        if (source < amount) return Code.INSUFFICIENT;
        if (amount > walletLimit - target) return Code.WALLET_LIMIT;
        balances.put(from, source - amount);
        treasuries.put(clan, target + amount);
        return Code.OK;
    }
    Code withdraw(String clanName, UUID to, long amount) {
        final String clan;
        try { clan = key(clanName); } catch (IllegalArgumentException ex) { return Code.BAD_AMOUNT; }
        if (!exists(to)) return Code.UNKNOWN_ACCOUNT;
        if (!treasuries.containsKey(clan)) return Code.UNKNOWN_TREASURY;
        if (amount <= 0) return Code.BAD_AMOUNT;
        long source = treasuries.get(clan), target = balance(to);
        if (source < amount) return Code.INSUFFICIENT;
        if (amount > walletLimit - target) return Code.WALLET_LIMIT;
        treasuries.put(clan, source - amount);
        balances.put(to, target + amount);
        return Code.OK;
    }
}
