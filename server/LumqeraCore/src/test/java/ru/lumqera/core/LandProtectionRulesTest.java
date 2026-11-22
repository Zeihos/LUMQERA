package ru.lumqera.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandProtectionRulesTest {
    @Test void pvpIsAllowedWithinOrdinaryClaims() {
        assertFalse(LandProtectionRules.denyEntityDamage(false, false, true, true, false));
    }

    @Test void spawnZoneBlocksPvpAndOtherAttacksRegardlessOfActor() {
        assertTrue(LandProtectionRules.denyEntityDamage(true, false, true, true, true));
        assertTrue(LandProtectionRules.denyEntityDamage(false, true, true, false, true));
        assertTrue(LandProtectionRules.denyEntityDamage(true, false, false, true, true));
    }

    @Test void hostilePlayersCannotKillProtectedPetsOrDestroyDecorativeEntities() {
        assertTrue(LandProtectionRules.denyEntityDamage(false, false, false, true, false));
        assertFalse(LandProtectionRules.denyEntityDamage(false, false, false, true, true));
        assertFalse(LandProtectionRules.denyEntityDamage(false, false, false, false, false));
    }

    @Test void unknownExplosionsCannotDamageClaimedNonPlayerEntities() {
        assertTrue(LandProtectionRules.denyEntityDamage(false, false, false, true, false));
    }

    @Test void crossBoundaryHopperTransfersAreBlockedWhenEitherSideIsProtected() {
        assertTrue(LandProtectionRules.denyCrossRegionTransfer("clan:nova", "wilderness", true, false));
        assertTrue(LandProtectionRules.denyCrossRegionTransfer("wilderness", "clan:nova", false, true));
        assertTrue(LandProtectionRules.denyCrossRegionTransfer("clan:nova", "clan:luna", true, true));
        assertFalse(LandProtectionRules.denyCrossRegionTransfer("clan:nova", "clan:nova", true, true));
        assertFalse(LandProtectionRules.denyCrossRegionTransfer("wilderness", "wilderness", false, false));
    }

    @Test void hopperCannotStealProtectedGroundItemsAcrossBoundary() {
        assertTrue(LandProtectionRules.denyExternalPickup("clan:nova", "wilderness", true));
        assertTrue(LandProtectionRules.denyExternalPickup("zone:spawn", "clan:nova", true));
        assertTrue(LandProtectionRules.denyExternalPickup("clan:nova", null, true));
        assertFalse(LandProtectionRules.denyExternalPickup("clan:nova", "clan:nova", true));
        assertFalse(LandProtectionRules.denyExternalPickup("wilderness", "clan:nova", false));
    }
}
