package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EconomyServiceTest {
    @TempDir Path folder;
    private final Logger log = Logger.getLogger("LumqeraEconomyTest");

    private EconomyService service() {
        return new EconomyService(folder.toFile(), log, 1_000_000L, 150L, 10_000L);
    }
    private void storage(String yaml) throws Exception {
        Files.writeString(folder.resolve("economy.yml"), yaml, StandardCharsets.UTF_8);
    }

    @Test void refusesNonSectionAccountsWithoutOverwritingOriginalData() throws Exception {
        String original = "schema-version: 2\naccounts: CORRUPTED\n";
        storage(original);
        EconomyService economy = service();
        economy.load();
        assertFalse(economy.healthy());
        assertFalse(economy.save());
        assertEquals(original, Files.readString(folder.resolve("economy.yml")));
    }

    @Test void refusesNonSectionTreasuriesWithoutOverwritingOriginalData() throws Exception {
        String original = "schema-version: 2\naccounts: {}\ntreasuries: [BROKEN]\n";
        storage(original);
        EconomyService economy = service();
        economy.load();
        assertFalse(economy.healthy());
        assertFalse(economy.save());
        assertEquals(original, Files.readString(folder.resolve("economy.yml")));
    }

    @Test void loadsExistingV1WalletData() throws Exception {
        UUID player = UUID.randomUUID();
        storage("schema-version: 1\naccounts:\n  " + player + ": 125\n");
        EconomyService economy = service();
        economy.load();
        assertTrue(economy.healthy());
        assertEquals(125, economy.balance(player));
    }

    @Test void loadsExistingV2WalletsAndTreasuries() throws Exception {
        UUID player = UUID.randomUUID();
        storage("schema-version: 2\naccounts:\n  " + player + ": 125\ntreasuries:\n  NOVA: 400\n");
        EconomyService economy = service();
        economy.load();
        assertTrue(economy.healthy());
        assertEquals(125, economy.balance(player));
        assertEquals(400, economy.treasuryBalance("nova"));
    }

    @Test void rejectsDuplicateCaseVariantUuidKeys() throws Exception {
        UUID player = UUID.randomUUID();
        storage("schema-version: 2\naccounts:\n  " + player + ": 125\n  " +
                player.toString().toUpperCase(java.util.Locale.ROOT) + ": 1000\n");
        EconomyService economy = service();
        economy.load();
        assertFalse(economy.healthy());
    }

    @Test void refusesUnexpectedTreasuriesInV1() throws Exception {
        storage("schema-version: 1\naccounts: {}\ntreasuries:\n  NOVA: 500\n");
        EconomyService economy = service();
        economy.load();
        assertFalse(economy.healthy());
    }
}
