package com.kaguya.custommobs.ai;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * AI無効化中のMobをteleportで移動させる共通ロジック。
 * <p>
 * バニラAIは{@code setAI(false)}で切ってあるため{@code setVelocity}は移動に反映されない
 * (詳細はCLAUDE.md参照)。teleportは当たり判定を無視するので、ここでブロックとの重なりを
 * 自前で見る。壁は通れず、1ブロックまでの段差は登り、ハーフブロックやカーペットの高さにも
 * 合わせる。足場が見つからない場合はその高さのまま進む(AI無効のMobは落下しないので、
 * 落とす処理は入れていない)。
 * <p>
 * {@link MeleeAttackBehavior}と{@link FollowOwnerBehavior}の両方から使う共通実装。
 */
public final class TeleportMovement {

    /** 1回の移動で登れる高さ / 降りられる高さ(ブロック) */
    private static final double STEP_UP = 1.0;
    private static final double STEP_DOWN = 3.0;
    /** ブロック境界ちょうどで重なったと誤判定しないための微小値 */
    private static final double EPSILON = 1.0E-7;

    private TeleportMovement() {
    }

    /**
     * selfをtargetLocに向けて最大distancePerTickぶんteleportで進める。
     * 真上/真下にいる、行き止まりで動けない等の場合は何もしない。
     */
    public static void moveToward(LivingEntity self, Location targetLoc, double distancePerTick) {
        Location selfLoc = self.getLocation();
        Vector dir = targetLoc.toVector().subtract(selfLoc.toVector());
        dir.setY(0);
        double horizontalDist = dir.length();
        if (horizontalDist < 1.0E-4) return; // 真上/真下にいる場合はnormalizeでNaNになるので動かさない
        dir.multiply(1.0 / horizontalDist);

        double step = Math.min(distancePerTick, horizontalDist);
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
     */
    private static Location resolveStandableLocation(LivingEntity self, Location from, Location desired) {
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
    private static Double findGroundY(World world, BoundingBox box, double fromY) {
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

    private static boolean hasRoom(BoundingBox selfBox, Location from, Location to) {
        World world = to.getWorld();
        if (world == null) return false;
        BoundingBox box = shifted(selfBox, from, to);
        if (box.getMinY() < world.getMinHeight() || box.getMaxY() > world.getMaxHeight()) return false;
        return !collides(world, box);
    }

    private static BoundingBox shifted(BoundingBox selfBox, Location from, Location to) {
        return selfBox.clone().shift(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }

    /** boxがブロックの当たり判定と重なるか。isPassable()なブロック(草・水など)は通り抜けられる扱い */
    private static boolean collides(World world, BoundingBox box) {
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
}
