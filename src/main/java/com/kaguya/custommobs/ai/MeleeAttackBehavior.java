package com.kaguya.custommobs.ai;

import com.kaguya.custommobs.model.AiBehaviorConfig;
import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public class MeleeAttackBehavior implements AiBehavior {

    private static final String COOLDOWN_KEY = "melee_attack";
    /** 索敵範囲の既定値は攻撃範囲の3倍。detect-range で個別に上書きできる */
    private static final double DETECT_RANGE_FACTOR = 3.0;
    /** 1回の移動で登れる高さ / 降りられる高さ(ブロック) */
    private static final double STEP_UP = 1.0;
    private static final double STEP_DOWN = 3.0;
    /** ブロック境界ちょうどで重なったと誤判定しないための微小値 */
    private static final double EPSILON = 1.0E-7;
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

        moveToward(self, selfLoc, target.getLocation());
    }

    /**
     * AI無効化中はsetVelocityが移動に反映されないため、直接座標を動かす。
     * ただし素のteleportだと壁をすり抜けて段差も登れないので、行き先に体が入る余地があるか
     * (=ブロックと重ならないか)と、足場があるかを確認してから移動する。
     */
    private void moveToward(LivingEntity self, Location selfLoc, Location targetLoc) {
        Vector dir = targetLoc.toVector().subtract(selfLoc.toVector());
        dir.setY(0);
        double horizontalDist = dir.length();
        if (horizontalDist < 1.0E-4) return; // 真上/真下にいる場合はnormalizeでNaNになるので動かさない
        dir.multiply(1.0 / horizontalDist);

        // 1Tickで目標を通り越さないように移動量を抑える
        double step = Math.min(movementSpeed(self) * 5.0, horizontalDist);
        if (step <= 0) return;

        Location desired = selfLoc.clone().add(dir.getX() * step, 0, dir.getZ() * step);
        Location next = resolveStandableLocation(self, selfLoc, desired);
        if (next == null) return; // 行き止まり

        next.setYaw((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
        next.setPitch(selfLoc.getPitch());
        self.teleport(next);
    }

    /**
     * 移動先の高さを解決する。移動先の足場の高さ(ハーフブロックやカーペットなど、
     * 1ブロック未満の高さも含む)にYを合わせる。足場が見つからないか体が入らない場合は
     * 同じ高さのままにフォールバックし、それも無理なら null(=行き止まり)を返す。
     * <p>
     * 足場が見つからないときにそのまま進めるのは、空中に湧いたMobがその場から
     * 動けなくなるのを避けるため。AI無効化中は落下しないので落とすこともできない。
     */
    private Location resolveStandableLocation(LivingEntity self, Location from, Location desired) {
        BoundingBox selfBox = self.getBoundingBox();
        World world = desired.getWorld();
        if (world == null) return null;

        Double groundY = findGroundY(world, shifted(selfBox, from, desired), from.getY());
        if (groundY != null) {
            Location candidate = desired.clone();
            candidate.setY(groundY);
            if (hasRoom(selfBox, from, candidate)) return candidate;
        }
        return hasRoom(selfBox, from, desired) ? desired : null;
    }

    /**
     * 移動先の足元にある足場の上面の高さを返す。登れる高さ(STEP_UP)を超えるブロックは
     * 足場とみなさない(壁として扱い、あとの体の判定で弾かれる)。
     *
     * @return 足場の上面のY。見つからなければ null
     */
    private Double findGroundY(World world, BoundingBox box, double fromY) {
        double ceiling = fromY + STEP_UP;
        int minX = floor(box.getMinX());
        int maxX = floor(box.getMaxX() - EPSILON);
        int minZ = floor(box.getMinZ());
        int maxZ = floor(box.getMaxZ() - EPSILON);
        int top = Math.min(world.getMaxHeight() - 1, floor(ceiling));
        int bottom = Math.max(world.getMinHeight(), floor(fromY - STEP_DOWN));

        Double best = null;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = top; y >= bottom; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isPassable()) continue;
                    double surface = block.getBoundingBox().getMaxY();
                    if (surface > ceiling + EPSILON) continue; // 高すぎて登れない
                    if (best == null || surface > best) best = surface;
                    break; // 同じ列ではこれより下は埋もれている
                }
            }
        }
        return best;
    }

    private boolean hasRoom(BoundingBox selfBox, Location from, Location to) {
        World world = to.getWorld();
        if (world == null) return false;
        BoundingBox box = shifted(selfBox, from, to);
        if (box.getMinY() < world.getMinHeight() || box.getMaxY() > world.getMaxHeight()) return false;
        return !collides(world, box);
    }

    private BoundingBox shifted(BoundingBox selfBox, Location from, Location to) {
        return selfBox.clone().shift(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    /** boxがブロックの当たり判定と重なるか。isPassable()なブロック(草・水など)は通り抜けられる扱い */
    private boolean collides(World world, BoundingBox box) {
        int minX = floor(box.getMinX());
        int maxX = floor(box.getMaxX() - EPSILON);
        int minY = Math.max(world.getMinHeight(), floor(box.getMinY()));
        int maxY = Math.min(world.getMaxHeight() - 1, floor(box.getMaxY() - EPSILON));
        int minZ = floor(box.getMinZ());
        int maxZ = floor(box.getMaxZ() - EPSILON);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isPassable()) continue;
                    if (block.getBoundingBox().overlaps(box)) return true;
                }
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
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
