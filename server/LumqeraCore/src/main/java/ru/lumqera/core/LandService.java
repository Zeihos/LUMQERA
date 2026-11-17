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
        LandRegistry.Result result = state.claim(clan.name, chunk(loc), maximum, true);
        if (result == LandRegistry.Result.OK) save();
        return result;
    }
    LandRegistry.Result unclaim(ClanRegistry.Clan clan, Location loc) {
        LandRegistry.Result result = state.unclaim(clan.name, chunk(loc));
        if (result == LandRegistry.Result.OK) save();
        return result;
    }
    int removeClan(String name) { int removed = state.removeClan(name); if (removed > 0) save(); return removed; }
    boolean setZone(String name, Location at, int radius) {
        boolean ok = state.setZone(name, at.getWorld().getUID(), at.getBlockX(), at.getBlockZ(), radius);
        if (ok) save(); return ok;
    }
    boolean removeZone(String name) { boolean ok = state.removeZone(name); if (ok) save(); return ok; }
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Object rawClaims = yaml.get("claims");
        if (rawClaims != null && !(rawClaims instanceof List<?>))
            throw new IllegalStateException("land.yml claims must be a YAML list");
        if (rawClaims instanceof List<?> items) for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> row))
                throw new IllegalStateException("land.yml contains a non-map claim");
            try {
                String clan = String.valueOf(row.get("clan"));
                UUID world = UUID.fromString(String.valueOf(row.get("world")));
                int x = Integer.parseInt(String.valueOf(row.get("x")));
                int z = Integer.parseInt(String.valueOf(row.get("z")));
                if (clans.byName(clan) == null || !state.restore(clan, new LandRegistry.Chunk(world, x, z)))
                    throw new IllegalStateException("Orphan/duplicate claim for " + clan + " at " + x + "," + z);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Malformed persisted land claim", ex);
            }
        }
        ConfigurationSection section = yaml.getConfigurationSection("zones");
        if (section == null && yaml.contains("zones")) throw new IllegalStateException("land.yml zones must be a map");
        if (section != null) for (String name : section.getKeys(false)) {
            try {
                String prefix = "zones." + name + ".";
                UUID world = UUID.fromString(yaml.getString(prefix + "world", ""));
                if (!state.setZone(name, world, yaml.getInt(prefix + "x"), yaml.getInt(prefix + "z"), yaml.getInt(prefix + "radius")))
                    throw new IllegalStateException("Invalid stored zone: " + name);
            } catch (IllegalArgumentException ex) { throw new IllegalStateException("Malformed zone " + name, ex); }
        }
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
