package ru.lumqera.core;

import java.util.*;

/** Pure Java model: a claim is an entire 16x16 chunk, addressed by world UUID and chunk X/Z.
 * Server main-thread only. The safe-zone precedence is enforced by LandService. */
final class LandRegistry {
    record Chunk(UUID world, int x, int z) {
        Chunk { Objects.requireNonNull(world); }
        static Chunk atBlock(UUID world, int blockX, int blockZ) {
            return new Chunk(world, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
        }
        List<Chunk> adjacent() {
            // Integer overflow must never make opposite edges of a world adjacent.
            List<Chunk> result = new ArrayList<>(4);
            if (x < Integer.MAX_VALUE) result.add(new Chunk(world, x + 1, z));
            if (x > Integer.MIN_VALUE) result.add(new Chunk(world, x - 1, z));
            if (z < Integer.MAX_VALUE) result.add(new Chunk(world, x, z + 1));
            if (z > Integer.MIN_VALUE) result.add(new Chunk(world, x, z - 1));
            return result;
        }
    }
    record Zone(String name, UUID world, int centerX, int centerZ, int radius) {
        Zone { Objects.requireNonNull(world); }
        boolean contains(UUID worldId, int x, int z) {
            return world.equals(worldId) && Math.abs((long) x - centerX) <= radius
                    && Math.abs((long) z - centerZ) <= radius;
        }
        boolean intersects(Chunk c) {
            if (!world.equals(c.world)) return false;
            long minX = (long)c.x * 16, minZ = (long)c.z * 16;
            return minX <= (long)centerX + radius && minX + 15 >= (long)centerX - radius
                    && minZ <= (long)centerZ + radius && minZ + 15 >= (long)centerZ - radius;
        }
    }
    enum Result { OK, INVALID_CLAN, ALREADY_CLAIMED, LIMIT_REACHED, NOT_ADJACENT, NOT_OWNED, DISCONNECTED, SAFE_ZONE, STORAGE_ERROR }

    private final Map<Chunk, String> claims = new HashMap<>();
    private final Map<String, Zone> zones = new TreeMap<>();
    static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    static boolean validZoneName(String name) { return name != null && name.matches("[A-Za-z0-9_]{3,24}"); }
    static boolean validRadius(int radius) { return radius >= 8 && radius <= 1024; }
    String owner(Chunk pos) { return claims.get(pos); }
    Collection<Map.Entry<Chunk, String>> allClaims() { return Collections.unmodifiableSet(claims.entrySet()); }
    Collection<Zone> zones() { return Collections.unmodifiableCollection(zones.values()); }
    Zone zone(String name) { return name == null ? null : zones.get(key(name)); }
    Zone zoneAt(UUID world, int blockX, int blockZ) {
        for (Zone z : zones.values()) if (z.contains(world, blockX, blockZ)) return z;
        return null;
    }
    boolean overlapsZone(Chunk chunk) {
        for (Zone z : zones.values()) if (z.intersects(chunk)) return true;
        return false;
    }
    int count(String clan) {
        if (clan == null) return 0;
        String key = key(clan);
        return (int) claims.values().stream().filter(key::equals).count();
    }
    /** Only for loading validated data; does not replace existing claims. */
    boolean restore(String clan, Chunk chunk) {
        if (!ClanRegistry.validName(clan) || claims.containsKey(chunk)) return false;
        claims.put(chunk, key(clan));
        return true;
    }
    Result claim(String clan, Chunk chunk, int max, boolean requireAdjacent) {
        if (!ClanRegistry.validName(clan)) return Result.INVALID_CLAN;
        String id = key(clan);
        if (claims.containsKey(chunk)) return Result.ALREADY_CLAIMED;
        if (overlapsZone(chunk)) return Result.SAFE_ZONE;
        if (count(id) >= Math.max(1, max)) return Result.LIMIT_REACHED;
        if (requireAdjacent && count(id) > 0 && chunk.adjacent().stream().noneMatch(p -> id.equals(owner(p))))
            return Result.NOT_ADJACENT;
        claims.put(chunk, id);
        return Result.OK;
    }
    /** Undo a newly created claim after a failed disk write, independent of topology. */
    boolean rollbackClaim(String clan, Chunk chunk) {
        return clan != null && claims.remove(chunk, key(clan));
    }
    Result unclaim(String clan, Chunk chunk) {
        if (clan == null || !key(clan).equals(owner(chunk))) return Result.NOT_OWNED;
        String owner = owner(chunk);
        claims.remove(chunk);
        if (!isConnected(owner)) {
            claims.put(chunk, owner);
            return Result.DISCONNECTED;
        }
        return Result.OK;
    }
    private boolean isConnected(String clan) {
        Set<Chunk> owned = new HashSet<>();
        claims.forEach((pos, owner) -> { if (owner.equals(clan)) owned.add(pos); });
        if (owned.isEmpty()) return true;
        Set<Chunk> seen = new HashSet<>();
        ArrayDeque<Chunk> queue = new ArrayDeque<>();
        Chunk start = owned.iterator().next();
        queue.add(start); seen.add(start);
        while (!queue.isEmpty()) {
            for (Chunk nearby : queue.remove().adjacent()) {
                if (owned.contains(nearby) && seen.add(nearby)) queue.add(nearby);
            }
        }
        return seen.size() == owned.size();
    }
    int removeClan(String clan) {
        if (clan == null) return 0;
        String id = key(clan);
        int before = claims.size();
        claims.entrySet().removeIf(e -> e.getValue().equals(id));
        return before - claims.size();
    }
    boolean setZone(String name, UUID world, int x, int z, int radius) {
        if (!validZoneName(name) || world == null || !validRadius(radius)) return false;
        zones.put(key(name), new Zone(name, world, x, z, radius));
        return true;
    }
    boolean removeZone(String name) { return name != null && zones.remove(key(name)) != null; }
}
