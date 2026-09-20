package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /cmob debugaim}用のパーティクル可視化。
 * <p>
 * バニラのF3+Bは実在するエンティティの当たり判定箱しか映さないが、
 * {@link MobManager#getAimPoint}が返す「視線コーン検索の狙い先」はy-offset/forward-offset
 * 補正込みの仮想的な点で、実体の当たり判定ではないためF3+Bには一切映らない。
 * これを実機で目視しながら調整できるように、狙い先の点と実際の当たり判定箱の両方を
 * パーティクルで描画する。
 * <ul>
 *   <li>赤い点線の箱: 本体(見えないMob本体)の実際の当たり判定
 *   <li>青い点線の箱: モデル用ArmorStandの実際の当たり判定
 *   <li>大きな白い光点: 視線コーン検索が使っている「狙い先」(getAimPoint)
 * </ul>
 */
public class AimDebugVisualizer {

    /** 表示対象を探す範囲(ブロック) */
    private static final double RANGE = 15.0;
    /** 箱の辺に沿ってパーティクルを打つ間隔(ブロック) */
    private static final double STEP = 0.2;

    private final MobManager mobManager;
    private final Set<UUID> enabled = new HashSet<>();

    public AimDebugVisualizer(MobManager mobManager) {
        this.mobManager = mobManager;
    }

    /** @return 切り替え後の状態(true=ON) */
    public boolean toggle(Player player) {
        if (enabled.remove(player.getUniqueId())) return false;
        enabled.add(player.getUniqueId());
        return true;
    }

    /** BukkitSchedulerから数tickおきに呼ばれる想定 */
    public void tick() {
        if (enabled.isEmpty()) return;

        for (UUID uuid : enabled) {
            Player player = org.bukkit.Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            for (CustomMobInstance instance : mobManager.getActiveInstances()) {
                if (!instance.getEntity().getWorld().equals(player.getWorld())) continue;
                if (instance.getEntity().getLocation().distanceSquared(player.getLocation()) > RANGE * RANGE) continue;

                drawBox(player, instance.getEntity().getBoundingBox(), Color.RED);
                ArmorStand stand = instance.getModelStand();
                if (stand != null && stand.isValid()) {
                    drawBox(player, stand.getBoundingBox(), Color.BLUE);
                }

                Location aim = mobManager.getAimPoint(instance);
                player.spawnParticle(Particle.END_ROD, aim, 1, 0, 0, 0, 0);
            }
        }
    }

    private void drawBox(Player player, BoundingBox box, Color color) {
        Particle.DustOptions options = new Particle.DustOptions(color, 1.0f);
        double minX = box.getMinX(), minY = box.getMinY(), minZ = box.getMinZ();
        double maxX = box.getMaxX(), maxY = box.getMaxY(), maxZ = box.getMaxZ();

        // X方向の4辺
        drawEdge(player, minX, minY, minZ, maxX, minY, minZ, options);
        drawEdge(player, minX, minY, maxZ, maxX, minY, maxZ, options);
        drawEdge(player, minX, maxY, minZ, maxX, maxY, minZ, options);
        drawEdge(player, minX, maxY, maxZ, maxX, maxY, maxZ, options);
        // Y方向の4辺
        drawEdge(player, minX, minY, minZ, minX, maxY, minZ, options);
        drawEdge(player, maxX, minY, minZ, maxX, maxY, minZ, options);
        drawEdge(player, minX, minY, maxZ, minX, maxY, maxZ, options);
        drawEdge(player, maxX, minY, maxZ, maxX, maxY, maxZ, options);
        // Z方向の4辺
        drawEdge(player, minX, minY, minZ, minX, minY, maxZ, options);
        drawEdge(player, maxX, minY, minZ, maxX, minY, maxZ, options);
        drawEdge(player, minX, maxY, minZ, minX, maxY, maxZ, options);
        drawEdge(player, maxX, maxY, minZ, maxX, maxY, maxZ, options);
    }

    private void drawEdge(Player player, double x1, double y1, double z1, double x2, double y2, double z2,
                           Particle.DustOptions options) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) (length / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            player.spawnParticle(Particle.REDSTONE, x1 + dx * t, y1 + dy * t, z1 + dz * t, 1, 0, 0, 0, 0, options);
        }
    }
}
