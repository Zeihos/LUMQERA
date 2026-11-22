package ru.lumqera.core;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LandServiceTest {
    @TempDir Path folder;
    private static final Logger LOG = Logger.getLogger("LUMQERA land tests");
    private static final UUID WORLD = UUID.fromString("2f7d93f6-9806-4ee0-9e85-92ba1f434a2f");
    private static final UUID OWNER = UUID.fromString("0f7563dd-c27b-486c-94c1-9c687b8741b4");

    private ClansService clans() {
        ClansService result = new ClansService(folder.toFile(), LOG);
        assertTrue(result.create("NOVA", OWNER));
        return result;
    }
    private LandService service(ClansService clans) {
        return new LandService(folder.toFile(), LOG, clans);
    }
    private void stored(String yaml) throws Exception {
        Files.writeString(folder.resolve("land.yml"), yaml, StandardCharsets.UTF_8);
    }
    private static Location location(int x, int z) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if ("getUID".equals(method.getName())) return WORLD;
                    throw new UnsupportedOperationException("Unexpected World call: " + method.getName());
                });
        return new Location(world, x, 70, z);
    }

    @Test
    void loadsExistingClaimsAndZonesAfterSave() {
        ClansService clans = clans();
        LandService first = service(clans);
        assertTrue(first.state().restore("NOVA", new LandRegistry.Chunk(WORLD, 3, -2)));
        assertTrue(first.state().setZone("SPAWN", WORLD, 100, -100, 16));
        assertTrue(first.save());

        LandService restored = service(clans);
        restored.load();
        assertEquals("nova", restored.state().owner(new LandRegistry.Chunk(WORLD, 3, -2)));
        assertNotNull(restored.state().zone("spawn"));
        assertEquals(16, restored.state().zone("spawn").radius());
    }

    @Test
    void refusesBadClaimRowsWithoutChangingOriginalFile() throws Exception {
        ClansService clans = clans();
        String data = "claims:\n  - world: " + WORLD +
                "\n    clan: NOVA\n    x: 1.5\n    z: 0\n";
        stored(data);
        assertThrows(IllegalStateException.class, () -> service(clans).load());
        assertEquals(data, Files.readString(folder.resolve("land.yml")));

        stored("claims: BROKEN\n");
        assertThrows(IllegalStateException.class, () -> service(clans).load());
    }

    @Test
    void refusesMalformedSafeZoneNumbersInsteadOfDefaultingToZero() throws Exception {
        ClansService clans = clans();
        for (String radius : new String[]{"MISSING", "1.5", "2147483648", "\"16\"", "-1"}) {
            stored("zones:\n  SPAWN:\n    world: " + WORLD +
                    "\n    x: 0\n    z: 0\n    radius: " + radius + "\n");
            assertThrows(IllegalStateException.class, () -> service(clans).load(), "radius=" + radius);
        }
        stored("zones:\n  SPAWN:\n    world: " + WORLD + "\n    x: 0\n    z: 0\n");
        assertThrows(IllegalStateException.class, () -> service(clans).load(), "missing radius");
    }

    @Test
    void refusesCaseInsensitiveDuplicateZoneNames() throws Exception {
        ClansService clans = clans();
        stored("zones:\n  SPAWN:\n    world: " + WORLD + "\n    x: 0\n    z: 0\n    radius: 12\n" +
                "  spawn:\n    world: " + WORLD + "\n    x: 100\n    z: 100\n    radius: 12\n");
        assertThrows(IllegalStateException.class, () -> service(clans).load());
    }

    @Test
    void failedClaimAndUnclaimDiskWritesRollBackInMemory() throws Exception {
        ClansService clans = clans();
        LandService service = service(clans);
        Path blocked = folder.resolve("land.yml");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("keep.txt"), "untouched");
        ClanRegistry.Clan nova = clans.byName("NOVA");

        assertEquals(LandRegistry.Result.STORAGE_ERROR, service.claim(nova, location(0, 0), 5));
        assertNull(service.state().owner(new LandRegistry.Chunk(WORLD, 0, 0)));

        assertTrue(service.state().restore("NOVA", new LandRegistry.Chunk(WORLD, 0, 0)));
        assertEquals(LandRegistry.Result.STORAGE_ERROR, service.unclaim(nova, location(0, 0)));
        assertEquals("nova", service.state().owner(new LandRegistry.Chunk(WORLD, 0, 0)));
        assertEquals("untouched", Files.readString(blocked.resolve("keep.txt")));
    }

    @Test
    void failedZoneWritesRestorePreviousState() throws Exception {
        ClansService clans = clans();
        LandService service = service(clans);
        assertTrue(service.state().setZone("SPAWN", WORLD, 0, 0, 16));
        Path blocked = folder.resolve("land.yml");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("keep.txt"), "untouched");

        assertFalse(service.setZone("SPAWN", location(64, 64), 32));
        assertEquals(16, service.state().zone("SPAWN").radius());
        assertFalse(service.removeZone("SPAWN"));
        assertEquals(16, service.state().zone("SPAWN").radius());
        assertFalse(service.setZone("NEW", location(128, 128), 16));
        assertNull(service.state().zone("NEW"));
    }
}
