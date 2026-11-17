package ru.lumqera.core;

import java.util.*;

/** Bukkit-free staff warning ledger. No bans or automatic sanctions. */
final class WarningRegistry {
    record Warning(long id, UUID player, String moderator, String reason, long issuedAt) {}
    private final NavigableMap<Long, Warning> warnings = new TreeMap<>();
    private long nextId = 1;
    private final int maxPerPlayer;

    WarningRegistry(int maxPerPlayer) {
        if (maxPerPlayer < 1 || maxPerPlayer > 10_000) throw new IllegalArgumentException("Invalid warning limit");
        this.maxPerPlayer = maxPerPlayer;
    }
    static String normalizeReason(String raw) {
        if (raw == null) throw new IllegalArgumentException("Missing reason");
        String reason = raw.strip();
        if (reason.length() < 3 || reason.length() > 160 || reason.codePoints().anyMatch(Character::isISOControl)
                || reason.indexOf('§') >= 0) throw new IllegalArgumentException("Reason must be 3-160 printable characters");
        return reason;
    }
    static boolean validModerator(String name) { return name != null && name.matches("[A-Za-z0-9_]{3,16}|CONSOLE"); }
    long nextId() { return nextId; }
    Collection<Warning> all() { return List.copyOf(warnings.values()); }
    List<Warning> forPlayer(UUID player) {
        if (player == null) return List.of();
        return warnings.values().stream().filter(w -> w.player().equals(player)).toList();
    }
    Warning add(UUID player, String moderator, String rawReason, long now) {
        String reason = normalizeReason(rawReason);
        if (player == null || !validModerator(moderator) || now <= 0 || nextId == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid warning metadata");
        if (forPlayer(player).size() >= maxPerPlayer) throw new IllegalStateException("Warning history limit reached");
        Warning warning = new Warning(nextId++, player, moderator, reason, now);
        warnings.put(warning.id(), warning);
        return warning;
    }
    boolean remove(long id) { return warnings.remove(id) != null; }
    Warning get(long id) { return warnings.get(id); }
    void restore(Collection<Warning> data, long next) {
        if (data == null || next <= 0) throw new IllegalArgumentException("Invalid staff ledger");
        NavigableMap<Long, Warning> restored = new TreeMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (Warning w : data) {
            if (w == null || w.id() <= 0 || w.id() >= next || w.player() == null || !validModerator(w.moderator())
                    || !normalizeReason(w.reason()).equals(w.reason()) || w.issuedAt() <= 0
                    || restored.putIfAbsent(w.id(), w) != null)
                throw new IllegalArgumentException("Invalid warning record");
            if (counts.merge(w.player(), 1, Integer::sum) > maxPerPlayer)
                throw new IllegalArgumentException("Warning limit exceeded");
        }
        warnings.clear(); warnings.putAll(restored); nextId = next;
    }
}
