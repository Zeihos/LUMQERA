package ru.lumqera.core;

import java.util.*;

/** Persistent coordinates and short, replaceable warmup requests; pure Java for tests. */
final class TravelRegistry {
    record Point(UUID world, double x, double y, double z, float yaw, float pitch) {
        Point {
            if (world == null) throw new IllegalArgumentException("Missing world UUID");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch))
                throw new IllegalArgumentException("Nonfinite coordinate");
            if (y < -2048 || y > 4096 || Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984)
                throw new IllegalArgumentException("Coordinate outside safe limits");
        }
        LandRegistry.Chunk chunk() {
            return LandRegistry.Chunk.atBlock(world, (int)Math.floor(x), (int)Math.floor(z));
        }
    }
    enum Destination { SPAWN, CLAN_HOME }
    record Request(Destination destination, String clanKey, Point origin, long readyAt) {}
    private Point spawn;
    private final Map<String, Point> homes = new TreeMap<>();
    private final Map<UUID, Request> pending = new HashMap<>();

    Point spawn() { return spawn; }
    void setSpawn(Point point) { spawn = Objects.requireNonNull(point); }
    Point home(String clan) { return clan == null ? null : homes.get(LandRegistry.key(clan)); }
    void setHome(String clan, Point point) {
        if (!ClanRegistry.validName(clan)) throw new IllegalArgumentException("Invalid clan");
        homes.put(LandRegistry.key(clan), Objects.requireNonNull(point));
    }
    boolean deleteHome(String clan) { return clan != null && homes.remove(LandRegistry.key(clan)) != null; }
    Map<String, Point> homes() { return Collections.unmodifiableMap(homes); }
    boolean validHome(String clan, LandRegistry land) {
        if (clan == null || land == null) return false;
        Point point = home(clan);
        return point != null && LandRegistry.key(clan).equals(land.owner(point.chunk()))
                && land.zoneAt(point.world(), (int)Math.floor(point.x()), (int)Math.floor(point.z())) == null;
    }
    void begin(UUID player, Destination destination, String clan, Point origin, long now, long durationMs) {
        Objects.requireNonNull(player); Objects.requireNonNull(origin); Objects.requireNonNull(destination);
        long dur = Math.max(0, Math.min(durationMs, 120_000));
        pending.put(player, new Request(destination, clan, origin,
                now > Long.MAX_VALUE - dur ? Long.MAX_VALUE : now + dur));
    }
    Request pending(UUID player) { return pending.get(player); }
    Request ready(UUID player, long now) {
        Request request = pending.get(player);
        if (request == null || now < request.readyAt()) return null;
        pending.remove(player);
        return request;
    }
    boolean cancel(UUID player) { return pending.remove(player) != null; }
    boolean cancelIfMoved(UUID player, Point current, double tolerance) {
        Request request = pending.get(player);
        if (request == null) return false;
        Point origin = request.origin();
        if (!origin.world().equals(current.world())) return cancel(player);
        double dx = origin.x() - current.x(), dy = origin.y() - current.y(), dz = origin.z() - current.z();
        return dx * dx + dy * dy + dz * dz > tolerance * tolerance && cancel(player);
    }
    Set<UUID> waitingPlayers() { return Set.copyOf(pending.keySet()); }
}
