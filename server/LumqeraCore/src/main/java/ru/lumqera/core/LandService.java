package ru.lumqera.core;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Data-layer and authorization. No async Bukkit calls. */
final class LandService {
    private final File file;
    private final Logger logger;
    private final ClansService clans;
    private final LandRegistry state = new LandRegistry();
    LandService(File folder, Logger logger, ClansService clans) {
        this.file = new File(folder, "land.yml"); this.logger = logger; this.clans = clans;
    }
    LandRegistry state() { return state; }
    String owner(Location loc) { return state.owner(chunk(loc)); }
    LandRegistry.Zone zone(Location loc) { return state.zoneAt(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockZ()); }
    boolean protectedAt(Location loc) { return zone(loc) != null || owner(loc) != null; }
    String label(Location loc) {
        LandRegistry.Zone zone = zone(loc);
        return zone != null ? "zone:" + LandRegistry.key(zone.name()) :
                owner(loc) == null ? "wilderness" : "clan:" + owner(loc);
    }
    static LandRegistry.Chunk chunk(Location loc) {
        return new LandRegistry.Chunk(loc.getWorld().getUID(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }
    /** Global admin bypass applies to building only, never to spawn PvP restrictions. */
    boolean canModify(Player player, Location loc) {
        if (player.hasPermission("lumqera.land.bypass")) return true;
        if (zone(loc) != null) return false;
        String owner = owner(loc);
        if (owner == null) return true;
        ClanRegistry.Clan clan = clans.of(player.getUniqueId());
        return clan != null && LandRegistry.key(clan.name).equals(owner);
    }
    LandRegistry.Result claim(ClanRegistry.Clan clan, Location loc, int maximum) {
        LandRegistry.Chunk pos = chunk(loc);
        LandRegistry.Result result = state.claim(clan.name, pos, maximum, true);
        if (result != LandRegistry.Result.OK) return result;
        if (save()) return result;
        state.rollbackClaim(clan.name, pos);
        logger.severe("LAND claim failed to persist; in-memory claim rolled back");
        return LandRegistry.Result.STORAGE_ERROR;
    }
    LandRegistry.Result unclaim(ClanRegistry.Clan clan, Location loc) {
        LandRegistry.Chunk pos = chunk(loc);
        LandRegistry.Result result = state.unclaim(clan.name, pos);
        if (result != LandRegistry.Result.OK) return result;
        if (save()) return result;
        if (!state.restore(clan.name, pos))
            throw new IllegalStateException("LAND unclaim rollback failed: " + pos);
        logger.severe("LAND unclaim failed to persist; in-memory claim restored");
        return LandRegistry.Result.STORAGE_ERROR;
    }
    int removeClan(String name) { int removed = state.removeClan(name); if (removed > 0) save(); return removed; }
    boolean setZone(String name, Location at, int radius) {
        LandRegistry.Zone previous = state.zone(name);
        boolean ok = state.setZone(name, at.getWorld().getUID(), at.getBlockX(), at.getBlockZ(), radius);
        if (!ok) return false;
        if (save()) return true;
        if (previous == null) state.removeZone(name);
        else state.setZone(previous.name(), previous.world(), previous.centerX(), previous.centerZ(), previous.radius());
        logger.severe("LAND safe-zone change failed to persist; in-memory zone rolled back");
        return false;
    }
    boolean removeZone(String name) {
        LandRegistry.Zone previous = state.zone(name);
        if (!state.removeZone(name)) return false;
        if (save()) return true;
        if (previous == null || !state.setZone(previous.name(), previous.world(),
                previous.centerX(), previous.centerZ(), previous.radius()))
            throw new IllegalStateException("LAND zone removal rollback failed");
        logger.severe("LAND zone removal failed to persist; in-memory zone restored");
        return false;
    }
    /** No silent defaults or YAML coercion: corrupted claims must never be lost on the next save. */
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Object rawClaims = yaml.get("claims");
        if (yaml.contains("claims") && !(rawClaims instanceof List<?>))
            throw new IllegalStateException("land.yml: claims must be a YAML list");
        if (rawClaims instanceof List<?> items) for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> row))
                throw new IllegalStateException("land.yml: non-map claim");
            String clan = requiredString(row.get("clan"), "claim.clan");
            UUID world = requiredUuid(row.get("world"), "claim.world");
            int x = requiredInt(row.get("x"), "claim.x");
            int z = requiredInt(row.get("z"), "claim.z");
            if (clans.byName(clan) == null || !state.restore(clan, new LandRegistry.Chunk(world, x, z)))
                throw new IllegalStateException("land.yml: orphan or duplicate claim for " + clan + " at " + x + "," + z);
        }

        ConfigurationSection section = yaml.getConfigurationSection("zones");
        if (section == null && yaml.contains("zones"))
            throw new IllegalStateException("land.yml: zones must be a YAML section");
        Set<String> names = new HashSet<>();
        if (section != null) for (String name : section.getKeys(false)) {
            if (!LandRegistry.validZoneName(name) || !names.add(LandRegistry.key(name)))
                throw new IllegalStateException("land.yml: invalid or duplicate zone: " + name);
            ConfigurationSection zone = section.getConfigurationSection(name);
            if (zone == null) throw new IllegalStateException("land.yml: invalid zone section: " + name);
            UUID world = requiredUuid(zone.get("world"), "zone.world");
            int x = requiredInt(zone.get("x"), "zone.x");
            int z = requiredInt(zone.get("z"), "zone.z");
            int radius = requiredInt(zone.get("radius"), "zone.radius");
            if (!state.setZone(name, world, x, z, radius))
                throw new IllegalStateException("land.yml: invalid zone: " + name);
        }
    }

    private static String requiredString(Object raw, String label) {
        if (!(raw instanceof String value) || value.isBlank())
            throw new IllegalStateException("land.yml: invalid " + label);
        return value;
    }
    private static UUID requiredUuid(Object raw, String label) {
        String text = requiredString(raw, label);
        if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalStateException("land.yml: invalid " + label);
        UUID parsed = UUID.fromString(text);
        if (!parsed.toString().equalsIgnoreCase(text))
            throw new IllegalStateException("land.yml: non-canonical " + label);
        return parsed;
    }
    private static int requiredInt(Object raw, String label) {
        if (!(raw instanceof Integer || raw instanceof Long) ||
                ((Number) raw).longValue() < Integer.MIN_VALUE ||
                ((Number) raw).longValue() > Integer.MAX_VALUE)
            throw new IllegalStateException("land.yml: invalid " + label);
        return ((Number) raw).intValue();
    }
    boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> claimRows = new ArrayList<>();
        for (var entry : state.allClaims()) {
            var pos = entry.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("world", pos.world().toString()); row.put("x", pos.x()); row.put("z", pos.z()); row.put("clan", entry.getValue());
            claimRows.add(row);
        }
        yaml.set("claims", claimRows);
        for (LandRegistry.Zone zone : state.zones()) {
            String prefix = "zones." + zone.name() + ".";
            yaml.set(prefix + "world", zone.world().toString());
            yaml.set(prefix + "x", zone.centerX());
            yaml.set(prefix + "z", zone.centerZ());
            yaml.set(prefix + "radius", zone.radius());
        }
        return YamlStore.save(yaml, file.toPath(), logger);
    }
}
