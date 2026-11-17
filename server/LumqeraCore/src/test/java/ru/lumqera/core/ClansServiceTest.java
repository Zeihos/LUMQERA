package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ClansServiceTest {
    @TempDir Path folder;

    private static final UUID OWNER = UUID.fromString("0f7563dd-c27b-486c-94c1-9c687b8741b4");
    private static final UUID MEMBER = UUID.fromString("3ff9050f-0a6c-407e-9d7a-5b541b5793f1");
    private static final UUID OTHER = UUID.fromString("edc0192d-4f5f-4806-8af0-3c076c3e2fc1");

    private ClansService service() {
        return new ClansService(folder.toFile(), Logger.getLogger("LUMQERA clan tests"));
    }

    private String validClan(String officers, String influence) {
        return "clans:\n  NOVA:\n    owner: " + OWNER +
                "\n    members: [" + OWNER + ", " + MEMBER + "]" +
                "\n    officers: " + officers +
                "\n    influence: " + influence + "\n";
    }

    private void stored(String yaml) throws Exception {
        Files.writeString(folder.resolve("clans.yml"), yaml, StandardCharsets.UTF_8);
    }

    @Test
    void loadsExistingClanIncludingOfficerAndInfluence() throws Exception {
        stored(validClan("[" + MEMBER + "]", "125") +
                "players:\n  " + OWNER + ": Chief\n  " + MEMBER + ": Comrade\n");
        ClansService clans = service();
        clans.load();

        ClanRegistry.Clan nova = clans.byName("nova");
        assertNotNull(nova);
        assertEquals(OWNER, nova.owner);
        assertTrue(nova.officers.contains(MEMBER));
        assertEquals(125, nova.influence);
        assertEquals("Comrade", clans.knownName(MEMBER));
        assertEquals(nova, clans.of(MEMBER));
    }

    @Test
    void rejectsScalarOfficersInsteadOfDroppingAllPrivileges() throws Exception {
        String input = validClan("CORRUPTED", "5");
        stored(input);
        assertThrows(IllegalStateException.class, () -> service().load());
        assertEquals(input, Files.readString(folder.resolve("clans.yml")));
    }

    @Test
    void rejectsNonStringMembershipEntries() throws Exception {
        String input = validClan("[]", "5")
                .replace(MEMBER.toString() + "]", MEMBER + ", 42]");
        stored(input);
        assertThrows(IllegalStateException.class, () -> service().load());
        assertEquals(input, Files.readString(folder.resolve("clans.yml")));
    }

    @Test
    void rejectsDuplicateOfficerEntries() throws Exception {
        stored(validClan("[" + MEMBER + ", " + MEMBER + "]", "5"));
        assertThrows(IllegalStateException.class, () -> service().load());
    }

    @Test
    void rejectsFractionalAndOverflowInfluence() throws Exception {
        for (String value : List.of("1.5", "2147483648", "-2", "\"100\"")) {
            stored(validClan("[]", value));
            assertThrows(IllegalStateException.class, () -> service().load(), "influence=" + value);
        }
    }

    @Test
    void rejectsDuplicateUuidNicknameAliases() throws Exception {
        stored(validClan("[]", "0") + "players:\n  " + OWNER + ": Chief\n  " +
                OWNER.toString().toUpperCase(Locale.ROOT) + ": Impostor\n");
        assertThrows(IllegalStateException.class, () -> service().load());
    }

    @Test
    void acceptsInvitationsAndRejectsStaleDisbandedClanObjects() {
        ClansService clans = service();
        assertTrue(clans.create("NOVA", OWNER));
        ClanRegistry.Clan nova = clans.byName("nova");
        assertTrue(clans.invite(nova, OWNER, MEMBER, 30_000L, 0L, 1_000L));
        assertEquals(nova, clans.accept(MEMBER, 10, 2_000L));
        assertEquals(nova, clans.of(MEMBER));
        assertTrue(clans.disband(nova));
        assertFalse(clans.invite(nova, OWNER, OTHER, 30_000L, 0L, 3_000L));
    }

    @Test
    void hugeInviteDurationCannotOverflowIntoExpiredTimestamp() {
        ClansService clans = service();
        assertTrue(clans.create("NOVA", OWNER));
        assertTrue(clans.invite(clans.byName("NOVA"), OWNER, MEMBER,
                10_000L, 0L, Long.MAX_VALUE - 100L));
        assertNotNull(clans.accept(MEMBER, 10, Long.MAX_VALUE - 50L));
    }
}
