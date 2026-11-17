package ru.lumqera.core;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;

import java.util.List;
import java.util.Objects;

/** Conservative protections for the first closed test. Not a WorldGuard replacement. */
final class LandProtectionListener implements Listener {
    private final LandService land;
    LandProtectionListener(LandService land) { this.land = land; }

    private void denied(Player p) { p.sendMessage("§5§lLUMQERA §8» §cЭта территория защищена."); }
    private boolean disallowed(Player p, Location loc) {
        if (land.canModify(p, loc)) return false;
        denied(p); return true;
    }
    private Player actor(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (disallowed(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (disallowed(e.getPlayer(), e.getBlockPlaced().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if ((e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL) || e.getClickedBlock() == null) return;
        if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) return;
        if (disallowed(e.getPlayer(), e.getClickedBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (disallowed(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (disallowed(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (disallowed(e.getPlayer(), e.getBlock().getLocation()) || disallowed(e.getPlayer(), e.getBlockClicked().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (disallowed(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null && disallowed(e.getPlayer(), e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = actor(e.getRemover());
        if (p == null ? land.protectedAt(e.getEntity().getLocation()) : disallowed(p, e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        Player attacker = actor(e.getDamager());
        boolean safeVictim = land.zone(e.getEntity().getLocation()) != null;
        boolean safeAttacker = attacker != null && land.zone(attacker.getLocation()) != null;
        if (safeVictim || safeAttacker) { e.setCancelled(true); return; }
        if (attacker != null && !(e.getEntity() instanceof Player)
                && !land.canModify(attacker, e.getEntity().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!land.protectedAt(e.getItem().getLocation())) return;
        if (!(e.getEntity() instanceof Player player) || !land.canModify(player, e.getItem().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperTransfer(InventoryMoveItemEvent e) {
        Location from = e.getSource().getLocation();
        Location to = e.getDestination().getLocation();
        if (from != null && to != null && !Objects.equals(land.label(from), land.label(to))
                && (land.protectedAt(from) || land.protectedAt(to))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (e.getBlocks().stream().anyMatch(state -> land.protectedAt(state.getBlock().getLocation()) &&
                (e.getPlayer() == null || !land.canModify(e.getPlayer(), state.getBlock().getLocation())))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent e) {
        String sourceRegion = land.label(e.getBlock().getLocation());
        if (e.getBlocks().stream().anyMatch(state -> !Objects.equals(sourceRegion, land.label(state.getBlock().getLocation()))
                && land.protectedAt(state.getBlock().getLocation()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (land.protectedAt(e.getBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTrample(org.bukkit.event.entity.EntityInteractEvent e) {
        if (land.protectedAt(e.getBlock().getLocation())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> land.protectedAt(block.getLocation()));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(block -> land.protectedAt(block.getLocation()));
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (touchesProtected(e.getBlock(), e.getBlocks(), e.getDirection().getModX(), e.getDirection().getModY(), e.getDirection().getModZ())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (touchesProtected(e.getBlock(), e.getBlocks(), -e.getDirection().getModX(), -e.getDirection().getModY(), -e.getDirection().getModZ())) e.setCancelled(true);
    }
    private boolean touchesProtected(Block piston, List<Block> blocks, int dx, int dy, int dz) {
        if (land.protectedAt(piston.getLocation()) || land.protectedAt(piston.getRelative(dx, dy, dz).getLocation())) return true;
        for (Block block : blocks) if (land.protectedAt(block.getLocation()) || land.protectedAt(block.getRelative(dx, dy, dz).getLocation())) return true;
        return false;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!Objects.equals(land.label(e.getBlock().getLocation()), land.label(e.getToBlock().getLocation()))
                && (land.protectedAt(e.getBlock().getLocation()) || land.protectedAt(e.getToBlock().getLocation()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (land.protectedAt(e.getBlock().getLocation()) &&
                (e.getPlayer() == null || !land.canModify(e.getPlayer(), e.getBlock().getLocation()))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (land.protectedAt(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (land.protectedAt(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        if (e.getBlocks().stream().anyMatch(block -> land.protectedAt(block.getBlock().getLocation()) &&
                (e.getPlayer() == null || !land.canModify(e.getPlayer(), block.getBlock().getLocation())))) e.setCancelled(true);
    }
}
