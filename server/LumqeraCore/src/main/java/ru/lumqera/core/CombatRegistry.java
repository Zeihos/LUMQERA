package ru.lumqera.core;

import java.util.*;

/** Pure logic for PvP counters; names and UUIDs are kept separate. */
final class CombatRegistry {
    enum Result { DEATH_ONLY, CREDITED, FARM_BLOCKED }
    static final class Stats {
        int kills;
        int deaths;
        Stats(int kills, int deaths) { this.kills = Math.max(0, kills); this.deaths = Math.max(0, deaths); }
        double kd() { return deaths == 0 ? kills : (double) kills / deaths; }
    }
    private final Map<UUID, Stats> data = new HashMap<>();
    private final Map<String, Long> recentPairs = new HashMap<>();

    Map<UUID, Stats> all() { return Collections.unmodifiableMap(data); }
    Stats get(UUID player) { return data.getOrDefault(player, new Stats(0, 0)); }
    void restore(UUID id, int kills, int deaths) { data.put(id, new Stats(kills, deaths)); }
    void restoreRecentPair(String id, long time) {
        if (id.matches("[0-9a-fA-F-]{36}:[0-9a-fA-F-]{36}") && time > 0) recentPairs.put(id, time);
    }
    Map<String, Long> recentPairs(long now, long cooldownMs) {
        prune(now, cooldownMs);
        return Collections.unmodifiableMap(recentPairs);
    }
    Result recordDeath(UUID victim, UUID killer, boolean allies, long now, long cooldownMs) {
        data.computeIfAbsent(victim, ignored -> new Stats(0, 0)).deaths++;
        if (killer == null || killer.equals(victim) || allies) return Result.DEATH_ONLY;
        String pair = killer + ":" + victim;
        Long last = recentPairs.get(pair);
        if (last != null && (now < last || now - last < Math.max(0, cooldownMs))) return Result.FARM_BLOCKED;
        recentPairs.put(pair, now);
        data.computeIfAbsent(killer, ignored -> new Stats(0, 0)).kills++;
        return Result.CREDITED;
    }
    void prune(long now, long cooldownMs) {
        recentPairs.entrySet().removeIf(e -> e.getValue() > now || now - e.getValue() >= Math.max(0, cooldownMs));
    }
}
