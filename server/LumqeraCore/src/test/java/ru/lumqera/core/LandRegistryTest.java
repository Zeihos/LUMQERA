package ru.lumqera.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LandRegistryTest {
    private static final UUID WORLD = UUID.fromString("2f7d93f6-9806-4ee0-9e85-92ba1f434a2f");
    private static final UUID OTHER_WORLD = UUID.fromString("33d15b1f-514b-4634-8c45-4c1b195788d0");

    private static LandRegistry.Chunk chunk(int x, int z) {
        return new LandRegistry.Chunk(WORLD, x, z);
    }

    @Test
    void claimsMustBeAdjacentInSameWorld() {
        LandRegistry land = new LandRegistry();
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(0, 0), 3, true));
        assertEquals(LandRegistry.Result.NOT_ADJACENT, land.claim("NOVA", chunk(2, 0), 3, true));
        assertEquals(LandRegistry.Result.NOT_ADJACENT,
                land.claim("NOVA", new LandRegistry.Chunk(OTHER_WORLD, 1, 0), 3, true));
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(1, 0), 3, true));
        assertEquals(LandRegistry.Result.LIMIT_REACHED, land.claim("NOVA", chunk(1, 1), 2, true));
        assertEquals(2, land.count("nova"));
    }

    @Test
    void unclaimRefusesToSplitTerritory() {
        LandRegistry land = new LandRegistry();
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(0, 0), 3, true));
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(1, 0), 3, true));
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(2, 0), 3, true));
        assertEquals(LandRegistry.Result.DISCONNECTED, land.unclaim("NOVA", chunk(1, 0)));
        assertEquals("nova", land.owner(chunk(1, 0)));
        assertEquals(3, land.count("nova"));
        assertEquals(LandRegistry.Result.OK, land.unclaim("NOVA", chunk(2, 0)));
        assertEquals(LandRegistry.Result.OK, land.unclaim("NOVA", chunk(1, 0)));
    }

    @Test
    void safeZonePreventsOverlappingNewClaims() {
        LandRegistry land = new LandRegistry();
        assertTrue(land.setZone("SPAWN", WORLD, 0, 0, 16));
        assertEquals(LandRegistry.Result.SAFE_ZONE, land.claim("NOVA", chunk(0, 0), 3, true));
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(2, 0), 3, true));
        assertEquals("nova", land.owner(chunk(2, 0)));
    }

    @Test
    void oppositeIntegerEdgesAreNeverAdjacent() {
        LandRegistry land = new LandRegistry();
        assertEquals(3, chunk(Integer.MAX_VALUE, 0).adjacent().size());
        assertEquals(3, chunk(Integer.MIN_VALUE, 0).adjacent().size());
        assertEquals(3, chunk(0, Integer.MAX_VALUE).adjacent().size());
        assertEquals(3, chunk(0, Integer.MIN_VALUE).adjacent().size());
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(Integer.MAX_VALUE, 0), 5, true));
        assertEquals(LandRegistry.Result.NOT_ADJACENT,
                land.claim("NOVA", chunk(Integer.MIN_VALUE, 0), 5, true));
    }

    @Test
    void rollbackRemovesOnlyTheNewlyAddedClaim() {
        LandRegistry land = new LandRegistry();
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(0, 0), 4, true));
        assertEquals(LandRegistry.Result.OK, land.claim("NOVA", chunk(1, 0), 4, true));
        assertFalse(land.rollbackClaim("OTHER", chunk(1, 0)));
        assertEquals(2, land.count("NOVA"));
        assertTrue(land.rollbackClaim("NOVA", chunk(1, 0)));
        assertEquals(1, land.count("NOVA"));
        assertEquals("nova", land.owner(chunk(0, 0)));
        assertNull(land.owner(chunk(1, 0)));
    }
}
