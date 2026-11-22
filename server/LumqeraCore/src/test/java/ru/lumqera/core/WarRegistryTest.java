package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WarRegistryTest {
    @Test void challengeAndWinByScoreAreExactlyOnce() {
        WarRegistry wars = new WarRegistry();
        long now = 100_000L;
        assertEquals(WarRegistry.Result.OK, wars.challenge("NOVA", "LUNA", now, 10_000));
        assertEquals(WarRegistry.Result.ALREADY_BUSY, wars.challenge("LUNA", "OTHER", now, 10_000));
        assertEquals(WarRegistry.Result.OK, wars.accept("LUNA", "NOVA", now + 100, 60_000, 2));
        assertEquals(WarRegistry.Result.NOT_FOUND, wars.accept("LUNA", "NOVA", now + 200, 60_000, 2));
        WarRegistry.Hit first = wars.creditedKill("NOVA", "LUNA", now + 300);
        assertTrue(first.counted());
        assertNull(first.ended());
        assertFalse(wars.creditedKill("NOVA", "NOVA", now + 400).counted());
        WarRegistry.Hit second = wars.creditedKill("NOVA", "LUNA", now + 500);
        assertTrue(second.counted());
        assertEquals("nova", second.ended().winner());
        assertEquals("luna", second.ended().loser());
        assertNull(wars.war("NOVA"));
        assertEquals(1, wars.wins("NOVA"));
        assertEquals(1, wars.losses("LUNA"));
        assertFalse(wars.creditedKill("NOVA", "LUNA", now + 600).counted());
        assertEquals(1, wars.wins("NOVA"));
    }

    @Test void timeLimitCanEndInDrawWithoutDuplicateRecords() {
        WarRegistry wars = new WarRegistry();
        assertEquals(WarRegistry.Result.OK, wars.challenge("NOVA", "LUNA", 1_000, 10_000));
        assertEquals(WarRegistry.Result.OK, wars.accept("LUNA", "NOVA", 2_000, 5_000, 3));
        assertTrue(wars.tick(6_999).isEmpty());
        List<WarRegistry.Finish> ended = wars.tick(7_000);
        assertEquals(1, ended.size());
        assertTrue(ended.getFirst().draw());
        assertEquals(1, wars.draws("NOVA"));
        assertEquals(1, wars.draws("LUNA"));
        assertTrue(wars.tick(8_000).isEmpty());
        assertEquals(1, wars.draws("NOVA"));
    }

    @Test void snapshotRestoresScoreAndHistoricTotalsAfterFinalization() {
        WarRegistry wars = new WarRegistry();
        assertEquals(WarRegistry.Result.OK, wars.challenge("NOVA", "LUNA", 1_000, 10_000));
        assertEquals(WarRegistry.Result.OK, wars.accept("LUNA", "NOVA", 2_000, 5_000, 1));
        WarRegistry.Snapshot snapshot = wars.snapshot();
        assertTrue(wars.creditedKill("LUNA", "NOVA", 3_000).counted());
        assertEquals(1, wars.wins("LUNA"));
        wars.restore(snapshot);
        assertNotNull(wars.war("NOVA"));
        assertEquals(0, wars.war("NOVA").aScore);
        assertEquals(0, wars.war("NOVA").bScore);
        assertEquals(0, wars.wins("LUNA"));
        assertTrue(wars.creditedKill("NOVA", "LUNA", 3_100).counted());
        assertEquals(1, wars.wins("NOVA"));
    }

    @Test void refusesInvalidWarParametersAndDuplicateRestores() {
        WarRegistry wars = new WarRegistry();
        assertFalse(wars.restoreWar("NOVA","NOVA",1000,2,0,0));
        assertFalse(wars.restoreWar("NOVA","LUNA",1000,0,0,0));
        assertFalse(wars.restoreWar("NOVA","LUNA",1000,2,2,0));
        assertTrue(wars.restoreWar("NOVA","LUNA",1000,2,1,0));
        assertFalse(wars.restoreWar("NOVA","OTHER",1000,2,0,0));
        assertFalse(wars.restoreWar("OTHER","LUNA",1000,2,0,0));
        assertEquals(WarRegistry.Result.INVALID, wars.accept("LUNA","NOVA",-1,1000,2));
    }
}
