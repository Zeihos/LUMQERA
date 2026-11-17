package ru.lumqera.core;

import java.util.*;

/** Bukkit-independent, main-thread PvP timer. Expiry is exclusive. */
final class CombatTagRegistry {
    private final Map<UUID, Long> expiry = new HashMap<>();
    void tag(UUID first, UUID second, long now, long durationMillis) {
        if (first == null || second == null || first.equals(second) || durationMillis <= 0) return;
        long until = now > Long.MAX_VALUE - durationMillis ? Long.MAX_VALUE : now + durationMillis;
        expiry.merge(first, until, Math::max);
        expiry.merge(second, until, Math::max);
    }
    long remainingMillis(UUID player, long now) {
        if (player == null) return 0;
        long until = expiry.getOrDefault(player, 0L);
        if (until <= now) { expiry.remove(player); return 0; }
        return until - now;
    }
    boolean active(UUID player, long now) { return remainingMillis(player, now) > 0; }
    long remainingSeconds(UUID player, long now) {
        long ms = remainingMillis(player, now);
        return ms == 0 ? 0 : 1 + (ms - 1) / 1000L;
    }
    void clear(UUID player) { expiry.remove(player); }
    void prune(long now) { expiry.entrySet().removeIf(e -> e.getValue() <= now); }
}
