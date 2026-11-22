package ru.lumqera.core;

import java.util.Objects;

/** Pure Java access decisions used by the Bukkit listener; no world or player mocking required. */
final class LandProtectionRules {
    private LandProtectionRules() {}

    /**
     * Safe zones block any attack, including regular player-vs-player damage.
     * Outside safe zones, clan claims still allow PvP between players, but
     * non-player entities can only be damaged by an authorized player.
     */
    static boolean denyEntityDamage(boolean victimInSafeZone, boolean actorInSafeZone,
                                    boolean victimIsPlayer, boolean victimProtected,
                                    boolean authorizedPlayerActor) {
        if (victimInSafeZone || actorInSafeZone) return true;
        return !victimIsPlayer && victimProtected && !authorizedPlayerActor;
    }

    /** Crossing a claim/zone boundary must not move items out of protected inventories. */
    static boolean denyCrossRegionTransfer(String source, String target,
                                           boolean sourceProtected, boolean targetProtected) {
        return (sourceProtected || targetProtected) && !Objects.equals(source, target);
    }

    /** A hopper outside a claimed region cannot collect dropped items from inside it. */
    static boolean denyExternalPickup(String itemRegion, String hopperRegion,
                                      boolean itemProtected) {
        return itemProtected && (hopperRegion == null || !Objects.equals(itemRegion, hopperRegion));
    }
}
