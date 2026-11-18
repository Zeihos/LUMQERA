package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StatsServiceTest {
    @TempDir Path folder;
    private static final UUID KILLER = UUID.fromString("359469e0-b4c9-4c0c-bb84-4f0464b0db02");
    private static final UUID VICTIM = UUID.fromString("187de467-f297-49d1-aeb7-79f0c8838702");
    private final Logger log = Logger.getLogger("LUMQERA PvP tests");

    private StatsService service() { return new StatsService(folder.toFile(), log, 30_000L); }

    private void stored(String yaml) throws Exception {
        Files.writeString(folder.resolve("pvp.yml"), yaml, StandardCharsets.UTF_8);
    }

    private static String encodedPair() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (KILLER + ":" + VICTIM).getBytes(StandardCharsets.UTF_8));
    }

    @Test void restoresKillsDeathsAndAntiFarmCooldown() throws Exception {
        stored("players:\n  " + KILLER + ":\n    kills: 5\n    deaths: 1\n  " +
                VICTIM + ":\n    kills: 0\n    deaths: 7\n" +
                "recent-pairs:\n  " + encodedPair() + ": 10000\n");
        StatsService service = service();
        service.load();
        assertEquals(5, service.of(KILLER).kills);
        assertEquals(7, service.of(VICTIM).deaths);
        assertEquals(CombatRegistry.Result.FARM_BLOCKED,
                service.state().recordDeath(VICTIM, KILLER, false, 11_000L, 30_000L));
    }

    @Test void rejectsDuplicateUuidAliasesWithoutOverwritingOriginal() throws Exception {
        String data = "players:\n  " + KILLER + ":\n    kills: 5\n    deaths: 1\n  " +
                KILLER.toString().toUpperCase(Locale.ROOT) + ":\n    kills: 999\n    deaths: 0\n";
        stored(data);
        assertThrows(IllegalStateException.class, () -> service().load());
        assertEquals(data, Files.readString(folder.resolve("pvp.yml")));
    }

    @Test void rejectsInvalidPlayerSectionsAndCounters() throws Exception {
        stored("players:\n  " + KILLER + ": BROKEN\n");
        assertThrows(IllegalStateException.class, () -> service().load());
        stored("players:\n  " + KILLER + ":\n    kills: 2.5\n    deaths: 0\n");
        assertThrows(IllegalStateException.class, () -> service().load());
        stored("players:\n  " + KILLER + ":\n    kills: 0\n    deaths: -1\n");
        assertThrows(IllegalStateException.class, () -> service().load());
    }

    @Test void rejectsInvalidPairTimestampAndMalformedEncoding() throws Exception {
        stored("recent-pairs:\n  " + encodedPair() + ": corrupt\n");
        assertThrows(IllegalStateException.class, () -> service().load());
        stored("recent-pairs:\n  " + encodedPair() + ": 1.5\n");
        assertThrows(IllegalStateException.class, () -> service().load());
        stored("recent-pairs:\n  notbase64: 123\n");
        assertThrows(IllegalStateException.class, () -> service().load());
    }

    @Test void failedDiskSaveCannotCreditWinForCoinsOrInfluence() throws Exception {
        Path blocked = folder.resolve("pvp.yml");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("keep.txt"), "do not remove");
        StatsService service = service();
        assertEquals(CombatRegistry.Result.PERSISTENCE_FAILED, service.recordDeath(VICTIM, KILLER, false));
        assertTrue(Files.exists(blocked.resolve("keep.txt")));
    }

    @Test void normalSaveRoundTripsCounts() {
        StatsService original = service();
        assertEquals(CombatRegistry.Result.CREDITED, original.recordDeath(VICTIM, KILLER, false));
        StatsService reloaded = service();
        reloaded.load();
        assertEquals(1, reloaded.of(KILLER).kills);
        assertEquals(1, reloaded.of(VICTIM).deaths);
    }
}
