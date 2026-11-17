package ru.lumqera.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Tracks PvP counters and retains cooldown pairs across restarts. */
final class StatsService {
    private final File file;
    private final Logger logger;
    private final CombatRegistry registry = new CombatRegistry();
    private final long farmCooldownMs;

    StatsService(File folder, Logger logger, long farmCooldownMs) {
        file = new File(folder, "pvp.yml"); this.logger = logger;
        this.farmCooldownMs = Math.max(0, farmCooldownMs);
    }
    CombatRegistry state() { return registry; }
    CombatRegistry.Stats of(UUID id) { return registry.get(id); }
    CombatRegistry.Result recordDeath(UUID victim, UUID killer, boolean allies) {
        CombatRegistry.Result result = registry.recordDeath(victim, killer, allies, System.currentTimeMillis(), farmCooldownMs);
        save(); return result;
    }
    /** Fail-closed: a malformed record must not be silently discarded and overwritten. */
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection people = yaml.getConfigurationSection("players");
        if (people == null && yaml.contains("players"))
            throw new IllegalStateException("pvp.yml: players must be a section");
        if (people != null) for (String id : people.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                int kills = nonNegativeInt(yaml.get("players." + id + ".kills"), "kills");
                int deaths = nonNegativeInt(yaml.get("players." + id + ".deaths"), "deaths");
                registry.restore(uuid, kills, deaths);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("pvp.yml: invalid player record " + id, exception);
            }
        }
        ConfigurationSection pairs = yaml.getConfigurationSection("recent-pairs");
        if (pairs == null && yaml.contains("recent-pairs"))
            throw new IllegalStateException("pvp.yml: recent-pairs must be a section");
        if (pairs != null) for (String encoded : pairs.getKeys(false)) {
            try {
                String key = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
                String[] uuids = key.split(":", -1);
                if (uuids.length != 2 || !UUID.fromString(uuids[0]).toString().equals(uuids[0]) ||
                        !UUID.fromString(uuids[1]).toString().equals(uuids[1]))
                    throw new IllegalArgumentException("invalid UUID pair");
                Object raw = yaml.get("recent-pairs." + encoded);
                if (!(raw instanceof Number n) || n.longValue() <= 0 ||
                        n.doubleValue() != (double)n.longValue())
                    throw new IllegalArgumentException("invalid pair timestamp");
                registry.restoreRecentPair(key, n.longValue());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("pvp.yml: invalid recent-pair record " + encoded, exception);
            }
        }
    }
    private static int nonNegativeInt(Object raw, String field) {
        if (!(raw instanceof Number number) || number.doubleValue() != (double)number.longValue()
                || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid " + field);
        return number.intValue();
    }
    boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : registry.all().entrySet()) {
            String prefix = "players." + entry.getKey();
            yaml.set(prefix + ".kills", entry.getValue().kills);
            yaml.set(prefix + ".deaths", entry.getValue().deaths);
        }
        for (var entry : registry.recentPairs(System.currentTimeMillis(), farmCooldownMs).entrySet()) {
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            yaml.set("recent-pairs." + key, entry.getValue());
        }
        return YamlStore.save(yaml, file.toPath(), logger);
    }
}
