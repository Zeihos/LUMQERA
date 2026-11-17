package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** World-UUID based locations persist across restart without auto-loading missing worlds. */
final class TravelService {
    private final File file;
    private final Logger logger;
    private final TravelRegistry registry = new TravelRegistry();

    TravelService(File folder, Logger logger) { this.file = new File(folder, "travel.yml"); this.logger = logger; }
    TravelRegistry state() { return registry; }
    static TravelRegistry.Point point(Location loc) {
        return new TravelRegistry.Point(Objects.requireNonNull(loc.getWorld()).getUID(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }
    static Location centered(Location location) {
        return new Location(Objects.requireNonNull(location.getWorld()), location.getBlockX() + 0.5,
                location.getBlockY(), location.getBlockZ() + 0.5, location.getYaw(), location.getPitch());
    }
    Location location(TravelRegistry.Point point) {
        if (point == null) return null;
        World world = Bukkit.getWorld(point.world());
        return world == null ? null : new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }
    /** Must have solid ground and enough empty space for a standing player. */
    static boolean safe(Location dest) {
        if (dest == null || dest.getWorld() == null || !dest.getWorld().getWorldBorder().isInside(dest)) return false;
        int y = dest.getBlockY();
        if (y <= dest.getWorld().getMinHeight() || y + 1 >= dest.getWorld().getMaxHeight()) return false;
        return dest.getBlock().getType().isAir() && dest.clone().add(0, 1, 0).getBlock().getType().isAir()
                && dest.clone().add(0, -1, 0).getBlock().getType().isSolid();
    }
    /** Never skip a damaged spawn/home: this would silently erase it on the next save. */
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.contains("spawn")) {
            ConfigurationSection spawn = yaml.getConfigurationSection("spawn");
            if (spawn == null) throw new IllegalStateException("travel.yml: spawn must be a section");
            try { registry.setSpawn(parse(spawn, "spawn")); }
            catch (IllegalArgumentException ex) { throw new IllegalStateException("travel.yml: invalid saved spawn", ex); }
        }
        ConfigurationSection homes = yaml.getConfigurationSection("homes");
        if (homes == null && yaml.contains("homes"))
            throw new IllegalStateException("travel.yml: homes must be a section");
        if (homes != null) for (String clan : homes.getKeys(false)) {
            if (!ClanRegistry.validName(clan))
                throw new IllegalStateException("travel.yml: invalid home clan " + clan);
            ConfigurationSection saved = homes.getConfigurationSection(clan);
            if (saved == null) throw new IllegalStateException("travel.yml: invalid home section " + clan);
            try { registry.setHome(clan, parse(saved, "home for " + clan)); }
            catch (IllegalArgumentException ex) {
                throw new IllegalStateException("travel.yml: invalid home for " + clan, ex);
            }
        }
    }
    private static double numeric(ConfigurationSection section, String key, String label) {
        Object raw = section.get(key);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new IllegalArgumentException("Missing/invalid " + label + "." + key);
        return number.doubleValue();
    }
    private static TravelRegistry.Point parse(ConfigurationSection section, String label) {
        try {
            return new TravelRegistry.Point(UUID.fromString(Objects.requireNonNull(section.getString("world"))),
                    numeric(section, "x", label), numeric(section, "y", label), numeric(section, "z", label),
                    (float)numeric(section, "yaw", label), (float)numeric(section, "pitch", label));
        } catch (NullPointerException ex) {
            throw new IllegalArgumentException("Missing " + label + ".world", ex);
        }
    }
    private static void write(YamlConfiguration yaml, String prefix, TravelRegistry.Point point) {
        yaml.set(prefix + ".world", point.world().toString());
        yaml.set(prefix + ".x", point.x()); yaml.set(prefix + ".y", point.y()); yaml.set(prefix + ".z", point.z());
        yaml.set(prefix + ".yaw", point.yaw()); yaml.set(prefix + ".pitch", point.pitch());
    }
    boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (registry.spawn() != null) write(yaml, "spawn", registry.spawn());
        registry.homes().forEach((clan, point) -> write(yaml, "homes." + clan, point));
        return YamlStore.save(yaml, file.toPath(), logger);
    }
    boolean validHome(String clan, LandService land) {
        return registry.validHome(clan, land.state());
    }
    void invalidateHomeIfUnclaimed(String clan, LandService land) {
        if (registry.home(clan) != null && !validHome(clan, land)) { registry.deleteHome(clan); save(); }
    }
}
