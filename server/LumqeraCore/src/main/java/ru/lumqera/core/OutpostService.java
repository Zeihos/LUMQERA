package ru.lumqera.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Bukkit adapter. Only real, alive, non-spectator clan members count toward capture. */
final class OutpostService {
    private final File file;
    private final Logger logger;
    private final ClansService clans;
    private final LandService land;
    private final OutpostRegistry state=new OutpostRegistry();
    OutpostService(File folder,Logger logger,ClansService clans,LandService land) {
        this.file=new File(folder,"outposts.yml"); this.logger=logger; this.clans=clans; this.land=land;
    }
    OutpostRegistry state() { return state; }
    /** A damaged point must abort startup, not disappear from the next saved snapshot. */
    void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection points = yaml.getConfigurationSection("points");
        if (points == null) {
            if (yaml.contains("points")) throw new IllegalStateException("outposts.yml: points must be a section");
            return;
        }
        for (String name : points.getKeys(false)) {
            try {
                String base = "points." + name;
                UUID world = UUID.fromString(Objects.requireNonNull(yaml.getString(base + ".world")));
                String owner = yaml.getString(base + ".owner");
                if (owner != null && (!ClanRegistry.validName(owner) || clans.byName(owner) == null))
                    throw new IllegalArgumentException("unknown owner clan: " + owner);
                int x = requiredInt(yaml.get(base + ".x"), "x");
                int y = requiredInt(yaml.get(base + ".y"), "y");
                int z = requiredInt(yaml.get(base + ".z"), "z");
                int radius = requiredInt(yaml.get(base + ".radius"), "radius");
                Object timestampRaw = yaml.get(base + ".rewarded-at");
                if (!(timestampRaw instanceof Number n) || n.longValue() < 0 ||
                        n.doubleValue() != (double)n.longValue())
                    throw new IllegalArgumentException("invalid rewarded-at");
                if (!state.restore(name, world, x, y, z, radius, owner, n.longValue()))
                    throw new IllegalArgumentException("duplicate/invalid point");
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new IllegalStateException("outposts.yml: invalid outpost " + name, ex);
            }
        }
    }
    private static int requiredInt(Object raw, String name) {
        if (!(raw instanceof Number n) || n.doubleValue() != (double)n.longValue() ||
                n.longValue() < Integer.MIN_VALUE || n.longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid " + name);
        return n.intValue();
    }
    boolean save() {
        YamlConfiguration yaml=new YamlConfiguration();
        for (OutpostRegistry.Point p:state.all()) {
            String root="points."+p.name;
            yaml.set(root+".world",p.world.toString()); yaml.set(root+".x",p.x);
            yaml.set(root+".y",p.y); yaml.set(root+".z",p.z); yaml.set(root+".radius",p.radius);
            yaml.set(root+".owner",p.owner); yaml.set(root+".rewarded-at",p.rewardedAt);
        }
        return YamlStore.save(yaml,file.toPath(),logger);
    }
    static boolean intersects(OutpostRegistry.Point point,LandRegistry.Chunk chunk) {
        if(!point.world.equals(chunk.world())) return false;
        long minX=(long)chunk.x()*16,minZ=(long)chunk.z()*16;
        return minX<=((long)point.x+point.radius)&&minX+15>=((long)point.x-point.radius)
                && minZ<=((long)point.z+point.radius)&&minZ+15>=((long)point.z-point.radius);
    }
    boolean create(String name,Location at,int radius,List<String> allowedWorlds) {
        if (at.getWorld()==null || !allowedWorlds.contains(at.getWorld().getName()) || land.zone(at)!=null)
            return false;
        // A point must not overlap even the edge of an existing clan claim or safe zone.
        OutpostRegistry.Point temporary=new OutpostRegistry.Point(name,at.getWorld().getUID(),
                at.getBlockX(),at.getBlockY(),at.getBlockZ(),radius);
        for (var entry:land.state().allClaims()) if (intersects(temporary,entry.getKey())) return false;
        for (var zone:land.state().zones()) {
            long dx=Math.abs((long)zone.centerX()-temporary.x),dz=Math.abs((long)zone.centerZ()-temporary.z);
            if(zone.world().equals(temporary.world) && dx<=zone.radius()+radius && dz<=zone.radius()+radius) return false;
        }
        if (!state.set(name,at.getWorld().getUID(),at.getBlockX(),at.getBlockY(),at.getBlockZ(),radius)) return false;
        save(); return true;
    }
    void tick(long now,int minMembers,long captureMs,long rewardMs,int reward) {
        for (OutpostRegistry.Point p:state.all()) {
            Map<String,Integer> present=new HashMap<>();
            for (Player player:Bukkit.getOnlinePlayers()) {
                if (!player.isOnline() || player.isDead() || player.getGameMode()==GameMode.SPECTATOR
                        || !player.getWorld().getUID().equals(p.world)) continue;
                ClanRegistry.Clan clan=clans.of(player.getUniqueId());
                if (clan==null) continue;
                Location pos=player.getLocation();
                double dx=pos.getX()-(p.x+0.5), dz=pos.getZ()-(p.z+0.5);
                if (dx*dx+dz*dz > (long)p.radius*p.radius || Math.abs(pos.getBlockY()-p.y)>12) continue;
                present.merge(ClanRegistry.key(clan.name),1,Integer::sum);
            }
            OutpostRegistry.Outcome outcome=state.update(p.name,present,now,minMembers,captureMs,rewardMs);
            if (outcome.type()==OutpostRegistry.OutcomeType.CAPTURED) {
                logger.info("OUTPOST_CAPTURE outpost="+p.name+" clan="+outcome.clan());
                Bukkit.broadcastMessage("§5§lLUMQERA §8» §dКлан §f"+outcome.clan()+" §dзахватил точку §f"+p.name);
                save();
            } else if (outcome.type()==OutpostRegistry.OutcomeType.REWARD) {
                // Persist cooldown BEFORE granting reward; at worst a crash loses a grant, never duplicates one.
                if (save() && clans.rewardClan(outcome.clan(),reward)) {
                    logger.info("OUTPOST_REWARD outpost="+p.name+" clan="+outcome.clan()+" influence="+reward);
                }
            }
        }
    }
}
