package com.kaguya.custommobs.ai;

import com.kaguya.custommobs.model.AiBehaviorConfig;
import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public class MeleeAttackBehavior implements AiBehavior {

    private static final String COOLDOWN_KEY = "melee_attack";
    /** 索敵範囲の既定値は攻撃範囲の3倍。detect-range で個別に上書きできる */
    private static final double DETECT_RANGE_FACTOR = 3.0;
    /** 移動速度attributeが取れなかったときのフォールバック */
    private static final double FALLBACK_SPEED = 0.25;

    @Override
    public void tick(CustomMobInstance mob, AiBehaviorConfig config, long nowTick) {
        LivingEntity self = mob.getEntity();
        double range = Math.max(0.1, config.getDouble("range", 2.0));
        int cooldownTicks = Math.max(1, config.getInt("cooldown-ticks", 20));
        double detectRange = Math.max(range, config.getDouble("detect-range", range * DETECT_RANGE_FACTOR));

        Location selfLoc = self.getLocation();
        Player target = findNearestPlayer(self, selfLoc, detectRange);
        if (target == null) return;

        if (selfLoc.distanceSquared(target.getLocation()) <= range * range) {
            if (mob.isReady(COOLDOWN_KEY, cooldownTicks, nowTick)) {
                target.damage(mob.getDefinition().getStats().getDamage(), self);
                mob.markUsed(COOLDOWN_KEY, nowTick);
            }
            return;
        }

        // 1Tickで目標を通り越さないように移動量を抑える
        TeleportMovement.moveToward(self, target.getLocation(), movementSpeed(self) * 5.0);
    }

    private double movementSpeed(LivingEntity self) {
        AttributeInstance attr = self.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        return attr == null ? FALLBACK_SPEED : attr.getValue();
    }

    private Player findNearestPlayer(LivingEntity self, Location selfLoc, double radius) {
        Player nearest = null;
        double nearestDistSq = radius * radius;
        for (Entity e : self.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof Player p) || !isTargetable(p)) continue;
            double d = p.getLocation().distanceSquared(selfLoc);
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = p;
            }
        }
        return nearest;
    }

    /** バニラ同様、クリエイティブ/スペクテイター/無敵/死亡中のプレイヤーは狙わない */
    private boolean isTargetable(Player player) {
        if (!player.isValid() || player.isDead() || player.isInvulnerable()) return false;
        GameMode mode = player.getGameMode();
        return mode != GameMode.SPECTATOR && mode != GameMode.CREATIVE;
    }
}
