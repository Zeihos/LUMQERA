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
        // Never grant coins/influence for an unpersisted PvP win.
        if (!save()) {
            logger.severe("PvP statistics could not be persisted; reward credit is blocked.");
            return CombatRegistry.Result.PERSISTENCE_FAILED;
        }
        return result;
    }
    /** Fail-closed: a malformed record must not be silently discarded and overwritten. */
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection people = yaml.getConfigurationSection("players");
        if (people == null && yaml.contains("players"))
            throw new IllegalStateException("pvp.yml: players must be a section");
        Set<UUID> knownPlayers = new HashSet<>();
        if (people != null) for (String id : people.getKeys(false)) {
            try {
                UUID uuid = canonicalUuid(id);
                if (!knownPlayers.add(uuid))
                    throw new IllegalArgumentException("duplicate player UUID");
                ConfigurationSection player = people.getConfigurationSection(id);
                if (player == null) throw new IllegalArgumentException("player must be a YAML section");
                int kills = nonNegativeInt(player.get("kills"), "kills");
                int deaths = nonNegativeInt(player.get("deaths"), "deaths");
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
                String canonicalEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!canonicalEncoded.equals(encoded)) throw new IllegalArgumentException("non-canonical encoded pair");
                String[] uuids = key.split(":", -1);
                if (uuids.length != 2 || !canonicalUuid(uuids[0]).toString().equals(uuids[0]) ||
                        !canonicalUuid(uuids[1]).toString().equals(uuids[1]))
                    throw new IllegalArgumentException("invalid UUID pair");
                Object raw = pairs.get(encoded);
                if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() <= 0)
                    throw new IllegalArgumentException("invalid pair timestamp");
                registry.restoreRecentPair(key, ((Number) raw).longValue());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("pvp.yml: invalid recent-pair record " + encoded, exception);
            }
        }
    }
    private static UUID canonicalUuid(String text) {
        if (text == null || !text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("invalid player UUID");
        UUID parsed = UUID.fromString(text);
        if (!parsed.toString().equalsIgnoreCase(text))
            throw new IllegalArgumentException("non-canonical UUID");
        return parsed;
    }
    private static int nonNegativeInt(Object raw, String field) {
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 0
                || ((Number) raw).longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid " + field);
        return ((Number) raw).intValue();
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
