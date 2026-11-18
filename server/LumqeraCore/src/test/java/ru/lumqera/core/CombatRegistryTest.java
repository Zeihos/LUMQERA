package ru.lumqera.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatRegistryTest {
    private static final UUID KILLER = UUID.fromString("359469e0-b4c9-4c0c-bb84-4f0464b0db02");
    private static final UUID VICTIM = UUID.fromString("187de467-f297-49d1-aeb7-79f0c8838702");
    private static final UUID OTHER = UUID.fromString("e37e793c-1128-4c23-9e82-d7ce1821cd41");

    @Test void creditsOneWinPerVictimDuringCooldownWhileCountingAllDeaths() {
        CombatRegistry stats = new CombatRegistry();
        long start = 1_000_000L;
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, start, 30_000L));
        assertEquals(CombatRegistry.Result.FARM_BLOCKED, stats.recordDeath(VICTIM, KILLER, false, start + 1_000L, 30_000L));
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(OTHER, KILLER, false, start + 1_000L, 30_000L));
        assertEquals(2, stats.get(KILLER).kills);
        assertEquals(2, stats.get(VICTIM).deaths);
        assertEquals(1, stats.get(OTHER).deaths);
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, start + 30_000L, 30_000L));
        assertEquals(3, stats.get(KILLER).kills);
    }

    @Test void excludesTeamKillsAndDeathsWithoutKillerFromRating() {
        CombatRegistry stats = new CombatRegistry();
        assertEquals(CombatRegistry.Result.DEATH_ONLY, stats.recordDeath(VICTIM, KILLER, true, 1000L, 30_000L));
        assertEquals(CombatRegistry.Result.DEATH_ONLY, stats.recordDeath(VICTIM, null, false, 2000L, 30_000L));
        assertEquals(2, stats.get(VICTIM).deaths);
        assertEquals(0, stats.get(KILLER).kills);
    }

    @Test void keepsAntiFarmProtectionWhenServerClockMovesBackwards() {
        CombatRegistry stats = new CombatRegistry();
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, 50_000L, 20_000L));
        assertEquals(1, stats.recentPairs(40_000L, 20_000L).size());
        assertEquals(CombatRegistry.Result.FARM_BLOCKED, stats.recordDeath(VICTIM, KILLER, false, 41_000L, 20_000L));
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, 70_000L, 20_000L));
    }

    @Test void zeroCooldownDoesNotBlockHitsOrCreateSavedPairs() {
        CombatRegistry stats = new CombatRegistry();
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, 100_000L, 0L));
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, 99_000L, 0L));
        assertEquals(2, stats.get(KILLER).kills);
        assertTrue(stats.recentPairs(100_000L, 0L).isEmpty());
    }

    @Test void countersSaturateInsteadOfBecomingNegative() {
        CombatRegistry stats = new CombatRegistry();
        stats.restore(KILLER, Integer.MAX_VALUE, 0);
        stats.restore(VICTIM, 0, Integer.MAX_VALUE);
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, 10_000L, 0L));
        assertEquals(Integer.MAX_VALUE, stats.get(KILLER).kills);
        assertEquals(Integer.MAX_VALUE, stats.get(VICTIM).deaths);
    }

    @Test void restoredPairStillProtectsRatingAfterRestart() {
        CombatRegistry stats = new CombatRegistry();
        stats.restoreRecentPair(KILLER + ":" + VICTIM, 10_000L);
        assertEquals(CombatRegistry.Result.FARM_BLOCKED, stats.recordDeath(VICTIM, KILLER, false, 15_000L, 30_000L));
        assertEquals(CombatRegistry.Result.CREDITED, stats.recordDeath(VICTIM, KILLER, false, 40_000L, 30_000L));
    }
}
