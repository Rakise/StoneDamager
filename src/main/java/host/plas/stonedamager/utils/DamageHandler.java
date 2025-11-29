package host.plas.stonedamager.utils;

import host.plas.bou.scheduling.TaskManager;
import host.plas.bou.utils.ClassHelper;
import host.plas.bou.utils.EntityUtils;
import host.plas.stonedamager.StoneDamager;
import host.plas.stonedamager.data.DamagableSelection;
import host.plas.stonedamager.events.ScheduledDamageEvent;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

public class DamageHandler {
    @Getter @Setter
    private static ConcurrentSkipListMap<DamagableSelection, Long> tickMap = new ConcurrentSkipListMap<>();

    public static void setTickable(DamagableSelection damagableSelection) {
        tickMap.put(damagableSelection, damagableSelection.getTicksPerDamage());
    }

    public static void unsetTickable(String identifier) {
        tickMap.forEach((damagableSelection, aLong) -> {
            if (damagableSelection.getIdentifier().equals(identifier)) {
                tickMap.remove(damagableSelection);
            }
        });
    }

    public static void addAllTickables(ConcurrentSkipListSet<DamagableSelection> tickMap) {
        tickMap.forEach(DamageHandler::setTickable);
    }

    public static void clearTickables() {
        tickMap.clear();
    }

    public static Optional<DamagableSelection> getTickable(String identifier) {
        return tickMap.keySet().stream().filter(d -> d.getIdentifier().equals(identifier)).findFirst();
    }

    public static long getTicksLeft(String identifier) {
        return getTickable(identifier).map(d -> tickMap.get(d)).orElse(1L);
    }

    public static void tickTicksLeft(String identifier) {
        getTickable(identifier).ifPresent(d -> {
            long ticks = tickMap.get(d);
            ticks -= 1;
            tickMap.put(d, ticks);
        });
    }

    public static void resetTicksLeft(String identifier) {
        getTickable(identifier).ifPresent(d -> {
            tickMap.put(d, d.getTicksPerDamage());
        });
    }

    public static void fire(LivingEntity entity, DamagableSelection damagableSelection, boolean isInSync) {
        ScheduledDamageEvent event = new ScheduledDamageEvent(entity, damagableSelection).fire();
        if (event.isCancelled()) return;

        try {
            if (isInSync) {
                fireInSync(event);
            } else {
                if (ClassHelper.isFolia()) {
                    TaskManager.getScheduler().runTask(entity, () -> fireInSync(event));
                } else {
                    TaskManager.getScheduler().runTask(() -> fireInSync(event));
                }
            }
        } catch (Throwable e) {
            StoneDamager.getInstance().logWarningWithInfo("Error while firing damage event.", e);
        }
    }

    // New helper: find nearest player within range
    private static Player getNearestPlayer(LivingEntity entity, double range) {
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Player p : entity.getWorld().getPlayers()) {
            if (!p.isOnline() || p.isDead()) continue;

            double dist = p.getLocation().distance(entity.getLocation());
            if (dist <= range && dist < nearestDist) {
                nearestDist = dist;
                nearest = p;
            }
        }

        return nearest;
    }

    public static void fireInSync(ScheduledDamageEvent event) {
        try {
            LivingEntity target = event.getEntity();
            double damage = event.getDamagableSelection().getDamageAmount();

            // Find closest player within 30 blocks
            Player attacker = getNearestPlayer(target, 30);

            if (attacker != null) {
                // Player-based damage: XP, drops, kill credit
                target.damage(damage, attacker);
            } else {
                // No player nearby: environmental damage
                target.damage(damage);
            }

        } catch (Throwable e) {
            StoneDamager.getInstance().logWarningWithInfo("Error while firing damage event in sync.", e);
        }
    }

    public static void tick() {
        try {
            getTickMap().forEach((damagableSelection, ticks) -> {
                if (!damagableSelection.isEnabled()) return;

                if (ticks > 0) {
                    tickTicksLeft(damagableSelection.getIdentifier());
                } else {
                    EntityUtils.collectEntitiesThenDo((entity) -> {
                        try {
                            if (ClassHelper.isFolia()) {
                                TaskManager.getScheduler().runTask(entity, getDamageTask(damagableSelection, entity));
                            } else {
                                TaskManager.getScheduler().runTask(getDamageTask(damagableSelection, entity));
                            }
                        } catch (Throwable e) {
                            StoneDamager.getInstance().logWarningWithInfo("Error while ticking entities.", e);
                        }
                    });

                    resetTicksLeft(damagableSelection.getIdentifier());
                }
            });

        } catch (Throwable e) {
            StoneDamager.getInstance().logWarningWithInfo("Error while ticking entities.", e);
        }
    }

    public static Runnable getDamageTask(DamagableSelection damagableSelection, Entity entity) {
        return () -> {
            if (!damagableSelection.isEnabled()) return;

            if (!(entity instanceof LivingEntity)) return;
            LivingEntity livingEntity = (LivingEntity) entity;

            if (damagableSelection.check(livingEntity)) {
                fire(livingEntity, damagableSelection, true);
            }
        };
    }
}
