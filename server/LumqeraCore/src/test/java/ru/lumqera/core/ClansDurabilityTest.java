package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ClansDurabilityTest {
    @TempDir Path folder;

    private static final UUID OWNER = UUID.fromString("657900c9-4d35-4bf3-9671-a127ec144e88");
    private static final UUID MEMBER = UUID.fromString("af25d660-e2ed-4136-9650-60528a4d312a");
    private static final UUID OTHER = UUID.fromString("eb360f4b-99fd-450f-a6f4-40d75c94a72e");
    private static final Logger LOG = Logger.getLogger("LUMQERA clan transactions");

    private ClansService service() {
        return new ClansService(folder.toFile(), LOG);
    }

    /** The nonempty directory makes atomic replacement of clans.yml fail on every OS. */
    private Path blockSaving() throws Exception {
        Path file = folder.resolve("clans.yml");
        Files.deleteIfExists(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep.txt"), "do not delete");
        return file;
    }

    private void allowSaving(Path blocked) throws Exception {
        assertEquals("do not delete", Files.readString(blocked.resolve("keep.txt")));
        Files.delete(blocked.resolve("keep.txt"));
        Files.delete(blocked);
    }

    @Test void failedCreateDoesNotLeavePhantomClanOrMember() throws Exception {
        Path blocked = blockSaving();
        ClansService clans = service();
        assertFalse(clans.create("NOVA", OWNER));
        assertNull(clans.byName("NOVA"));
        assertNull(clans.of(OWNER));
        allowSaving(blocked);
        assertTrue(clans.create("NOVA", OWNER));
        assertEquals("NOVA", clans.byName("nova").name);
    }

    @Test void failedInviteAcceptanceCanBeRetriedAfterStorageRecovers() throws Exception {
        ClansService clans = service();
        assertTrue(clans.create("NOVA", OWNER));
        assertTrue(clans.invite(clans.byName("NOVA"), OWNER, MEMBER, 50_000, 0, 1_000));
        Path blocked = blockSaving();
        assertNull(clans.accept(MEMBER, 10, 2_000));
        assertNull(clans.of(MEMBER));
        assertSame(clans.byName("NOVA"), clans.of(OWNER));
        allowSaving(blocked);
        assertNotNull(clans.accept(MEMBER, 10, 3_000));
        assertSame(clans.of(OWNER), clans.of(MEMBER));
        ClansService reload = service();
        reload.load();
        assertNotNull(reload.of(MEMBER));
    }

    @Test void failedRoleAndMembershipChangesRestoreSameClanInstance() throws Exception {
        ClansService clans = service();
        assertTrue(clans.create("NOVA", OWNER));
        assertTrue(clans.invite(clans.byName("NOVA"), OWNER, MEMBER, 60_000, 0, 1_000));
        assertNotNull(clans.accept(MEMBER, 10, 2_000));
        ClanRegistry.Clan original = clans.byName("NOVA");
        Path blocked = blockSaving();

        assertFalse(clans.promote(original, OWNER, MEMBER));
        assertFalse(original.officers.contains(MEMBER));
        assertFalse(clans.transfer(original, OWNER, MEMBER));
        assertEquals(OWNER, original.owner);
        assertFalse(clans.remove(original, MEMBER));
        assertTrue(original.members.contains(MEMBER));
        assertSame(original, clans.byName("NOVA"));
        assertSame(original, clans.of(MEMBER));

        allowSaving(blocked);
        assertTrue(clans.promote(original, OWNER, MEMBER));
        assertTrue(original.officers.contains(MEMBER));
        assertTrue(clans.demote(original, OWNER, MEMBER));
        assertFalse(original.officers.contains(MEMBER));
        assertTrue(clans.transfer(original, OWNER, MEMBER));
        assertEquals(MEMBER, original.owner);
    }

    @Test void failedDisbandKeepsClanAndPendingInvitations() throws Exception {
        ClansService clans = service();
        assertTrue(clans.create("NOVA", OWNER));
        ClanRegistry.Clan original = clans.byName("NOVA");
        assertTrue(clans.invite(original, OWNER, OTHER, 50_000, 0, 1_000));
        Path blocked = blockSaving();
        assertFalse(clans.disband(original));
        assertSame(original, clans.byName("NOVA"));
        assertSame(original, clans.of(OWNER));
        allowSaving(blocked);
        assertNotNull(clans.accept(OTHER, 10, 3_000));
        assertTrue(clans.disband(original));
        assertNull(clans.byName("NOVA"));
        assertNull(clans.of(OTHER));
    }

    @Test void failedInfluenceAndNicknameWritesDoNotClaimSuccess() throws Exception {
        ClansService clans = service();
        assertTrue(clans.create("NOVA", OWNER));
        clans.remember(OWNER, "Original");
        assertEquals("Original", clans.knownName(OWNER));
        ClanRegistry.Clan original = clans.byName("NOVA");
        Path blocked = blockSaving();
        assertFalse(clans.rewardClan("NOVA", 100));
        assertFalse(clans.reward(OWNER, 10));
        assertEquals(0, original.influence);
        clans.remember(OWNER, "Changed");
        assertEquals("Original", clans.knownName(OWNER));
        allowSaving(blocked);
        assertTrue(clans.reward(OWNER, 10));
        assertTrue(clans.rewardClan("NOVA", 5));
        assertEquals(15, original.influence);
        ClansService reloaded = service();
        reloaded.load();
        assertEquals(15, reloaded.byName("NOVA").influence);
        assertEquals("Original", reloaded.knownName(OWNER));
    }
}
