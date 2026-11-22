package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class WarServiceTest {
    @TempDir Path folder;
    private static final Logger LOG = Logger.getLogger("LUMQERA war tests");
    private static final UUID OWNER_A = UUID.fromString("09de7474-22ee-4ef1-9626-c7831d79a5f8");
    private static final UUID OWNER_B = UUID.fromString("f8bb91e4-4163-4631-922a-9b4f728ced26");

    private ClansService clans() {
        ClansService clans = new ClansService(folder.toFile(), LOG);
        assertTrue(clans.create("NOVA", OWNER_A));
        assertTrue(clans.create("LUNA", OWNER_B));
        return clans;
    }
    private WarService service() { return new WarService(folder.toFile(), LOG); }
    private void challenge(WarService wars) {
        assertEquals(WarRegistry.Result.OK, wars.state().challenge("NOVA","LUNA",1000,5000));
    }

    @Test void acceptedWarAndScoresSurviveServerRestart() {
        ClansService clans = clans();
        WarService original = service();
        challenge(original);
        assertEquals(WarRegistry.Result.OK, original.accept("LUNA","NOVA",2000,5000,3));
        assertTrue(original.creditedKill("NOVA","LUNA",3000).counted());
        assertEquals(1, original.state().war("NOVA").aScore);

        WarService restored = service();
        restored.load(clans);
        assertNotNull(restored.state().war("NOVA"));
        assertEquals(1, restored.state().war("NOVA").aScore);
        assertEquals(0, restored.state().war("NOVA").bScore);
        assertEquals(7_000, restored.state().war("NOVA").endsAt);
        assertEquals(1, restored.finishExpired(7_000).size());
        assertEquals(1, restored.state().wins("NOVA"));

        WarService afterEnd = service();
        afterEnd.load(clans);
        assertNull(afterEnd.state().war("NOVA"));
        assertEquals(1, afterEnd.state().wins("NOVA"));
        assertEquals(1, afterEnd.state().losses("LUNA"));
        assertTrue(afterEnd.finishExpired(9_000).isEmpty());
    }

    @Test void failedAcceptRestoresPendingChallenge() throws Exception {
        clans();
        WarService wars = service();
        challenge(wars);
        Path blocked = folder.resolve("wars.yml");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("keep.txt"), "unchanged");
        assertEquals(WarRegistry.Result.STORAGE_ERROR, wars.accept("LUNA","NOVA",2000,5000,3));
        assertNull(wars.state().war("NOVA"));
        assertNotNull(wars.state().challengeFor("LUNA"));
        assertEquals("unchanged", Files.readString(blocked.resolve("keep.txt")));
    }

    @Test void failedScoreWriteRestoresActiveWarAndHistoricTotals() throws Exception {
        clans();
        WarService wars = service();
        challenge(wars);
        assertEquals(WarRegistry.Result.OK, wars.accept("LUNA","NOVA",2000,5000,1));
        Path blocked = folder.resolve("wars.yml");
        Files.delete(blocked);
        Files.createDirectory(blocked);

        WarRegistry.Hit hit = wars.creditedKill("NOVA","LUNA",3000);
        assertFalse(hit.counted());
        assertTrue(hit.storageError());
        assertNull(hit.ended());
        assertNotNull(wars.state().war("NOVA"));
        assertEquals(0, wars.state().war("NOVA").aScore);
        assertEquals(0, wars.state().wins("NOVA"));
    }

    @Test void failedTimeExpiryAndSurrenderKeepWarActive() throws Exception {
        clans();
        WarService wars = service();
        challenge(wars);
        assertEquals(WarRegistry.Result.OK, wars.accept("LUNA","NOVA",2000,5000,3));
        Path blocked = folder.resolve("wars.yml");
        Files.delete(blocked);
        Files.createDirectory(blocked);
        assertTrue(wars.finishExpired(7000).isEmpty());
        assertNotNull(wars.state().war("NOVA"));
        assertEquals(0, wars.state().draws("NOVA"));
        WarService.Surrender surrendered = wars.surrender("NOVA");
        assertTrue(surrendered.storageError());
        assertNull(surrendered.finish());
        assertNotNull(wars.state().war("NOVA"));
        assertEquals(0, wars.state().wins("LUNA"));
    }

    @Test void malformedDiskRecordsDoNotDisappearAfterLoad() throws Exception {
        ClansService clans = clans();
        Files.writeString(folder.resolve("wars.yml"), "records:\n  NOVA:\n    wins: 1.5\n    losses: 0\n    draws: 0\n");
        assertThrows(IllegalStateException.class, () -> service().load(clans));
    }
}
