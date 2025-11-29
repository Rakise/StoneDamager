package host.plas.stonedamager.utils;

import host.plas.stonedamager.data.DamagableSelection;
import host.plas.stonedamager.events.ScheduledDamageEvent;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.concurrent.ConcurrentSkipListMap;

public class DamageHandler {
    @Getter @Setter
    private static ConcurrentSkipListMap<DamagableSelection, Long> tickMap = new ConcurrentSkipListMap<>();

    public static void setTickable(DamagableSelection damagableSelection) {
        tickMap.put(damagableSelection, damagableSelection.getTicksPerDamage());
    }

    public static void unsetTickable(String identifier) {
        tickMap.keySet().removeIf(selection -> selection.getIdentifier().equals(identifier));
    }

    public static void addAllTickables(Collection<DamagableSelection> selections) {
        selections.forEach(DamageHandler::setTickable);
    }

    /**
     * Helper: Find the closest player to a location to attribute the damage to.
     */
    private static Player getClosestPlayer(Location loc) {
        if (loc.getWorld() == null) return null;
        Player closest = null;
        double closestDist = Double.MAX_VALUE;

        // Scan for players within 15 blocks
        for (Player p : loc.getWorld().getPlayers()) {
            double dist = p.getLocation().distanceSquared(loc);
            if (dist < 225 && dist < closestDist) { // 15^2 = 225
                closestDist = dist;
                closest = p;
            }
        }
        return closest;
    }

    public static void tick() {
        if (tickMap.isEmpty()) return;

        tickMap.forEach((selection, ticksLeft) -> {
            if (ticksLeft <= 0) {
                // Reset the timer for this block
                tickMap.put(selection, selection.getTicksPerDamage());

                // Instead of guessing selection.getAffectedEntities(), we manually find them.
                // We assume selection.getLocation() exists. 
                Location loc = selection.getLocation();
                
                if (loc != null && loc.getWorld() != null) {
                    // Look for victims within 1 block of the stone cutter
                    Collection<Entity> nearby = loc.getWorld().getNearbyEntities(loc, 1.0, 1.0, 1.0);
                    
                    // Find the "owner" of this damage (closest player)
                    Player damager = getClosestPlayer(loc);

                    for (Entity entity : nearby) {
                        if (!(entity instanceof LivingEntity)) continue;
                        LivingEntity victim = (LivingEntity) entity;
                        if (victim.isDead()) continue;

                        double damage = selection.getDamageAmount();

                        // Fire event
                        ScheduledDamageEvent event = new ScheduledDamageEvent(victim, damage, selection);
                        Bukkit.getPluginManager().callEvent(event);

                        if (event.isCancelled()) continue;

                        // Deal the damage
                        if (damager != null) {
                            // Attributes damage to the player (drops XP)
                            victim.damage(event.getDamage(), damager);
                        } else {
                            // Fallback to generic damage
                            victim.damage(event.getDamage());
                        }
                    }
                }

            } else {
                tickMap.put(selection, ticksLeft - 1);
            }
        });
    }
}
