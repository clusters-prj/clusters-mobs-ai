package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.HashSet;
import java.util.Locale;
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
 * <p>
 * Bukkit APIの{@code Player#spawnParticle}/{@code World#spawnParticle}経由では
 * (原因不明のまま)実機で一切見えなかったため、実際に見えることを確認済みの
 * バニラ{@code /particle ... force}コマンドをコンソールから発行する形にしている。
 * <ul>
 *   <li>赤い点線の箱: 本体(見えないMob本体)の実際の当たり判定
 *   <li>青い点線の箱: モデル用ArmorStandの実際の当たり判定
 *   <li>大きな白い光点: 視線コーン検索が使っている「狙い先」(getAimPoint)
 * </ul>
 */
public class AimDebugVisualizer {

    /** 表示対象を探す範囲(ブロック) */
    private static final double RANGE = 15.0;
    /**
     * 箱の辺に沿ってパーティクルを打つ間隔(ブロック)。
     * 1点ごとにコンソールから{@code /particle}を発行するので、細かくしすぎると
     * コンソール/ログ(CoreProtect等のコマンドロガー)が埋まる。デバッグ用途なので粗めでよい
     */
    private static final double STEP = 0.5;

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

    /** ONにした瞬間の切り分け用。近くに対象が何体いるかを返す(見えない原因が検出漏れか描画かを切り分ける) */
    public int countNearby(Player player) {
        int count = 0;
        for (CustomMobInstance instance : mobManager.getActiveInstances()) {
            if (!instance.getEntity().getWorld().equals(player.getWorld())) continue;
            if (instance.getEntity().getLocation().distanceSquared(player.getLocation()) > RANGE * RANGE) continue;
            count++;
        }
        return count;
    }

    /** BukkitSchedulerから数tickおきに呼ばれる想定 */
    public void tick() {
        if (enabled.isEmpty()) return;

        for (UUID uuid : enabled) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            for (CustomMobInstance instance : mobManager.getActiveInstances()) {
                if (!instance.getEntity().getWorld().equals(player.getWorld())) continue;
                if (instance.getEntity().getLocation().distanceSquared(player.getLocation()) > RANGE * RANGE) continue;

                drawBox(player, instance.getEntity().getBoundingBox(), "1,0,0");
                ArmorStand stand = instance.getModelStand();
                if (stand != null && stand.isValid()) {
                    drawBox(player, stand.getBoundingBox(), "0,0.4,1");
                }

                Location aim = mobManager.getAimPoint(instance);
                dispatch(String.format(Locale.ROOT,
                        "particle end_rod %.3f %.3f %.3f 0.05 0.05 0.05 0.01 15 force %s",
                        aim.getX(), aim.getY(), aim.getZ(), player.getName()));
            }
        }
    }

    private void drawBox(Player player, BoundingBox box, String rgb) {
        double minX = box.getMinX(), minY = box.getMinY(), minZ = box.getMinZ();
        double maxX = box.getMaxX(), maxY = box.getMaxY(), maxZ = box.getMaxZ();

        // X方向の4辺
        drawEdge(player, minX, minY, minZ, maxX, minY, minZ, rgb);
        drawEdge(player, minX, minY, maxZ, maxX, minY, maxZ, rgb);
        drawEdge(player, minX, maxY, minZ, maxX, maxY, minZ, rgb);
        drawEdge(player, minX, maxY, maxZ, maxX, maxY, maxZ, rgb);
        // Y方向の4辺
        drawEdge(player, minX, minY, minZ, minX, maxY, minZ, rgb);
        drawEdge(player, maxX, minY, minZ, maxX, maxY, minZ, rgb);
        drawEdge(player, minX, minY, maxZ, minX, maxY, maxZ, rgb);
        drawEdge(player, maxX, minY, maxZ, maxX, maxY, maxZ, rgb);
        // Z方向の4辺
        drawEdge(player, minX, minY, minZ, minX, minY, maxZ, rgb);
        drawEdge(player, maxX, minY, minZ, maxX, minY, maxZ, rgb);
        drawEdge(player, minX, maxY, minZ, minX, maxY, maxZ, rgb);
        drawEdge(player, maxX, maxY, minZ, maxX, maxY, maxZ, rgb);
    }

    private void drawEdge(Player player, double x1, double y1, double z1, double x2, double y2, double z2, String rgb) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) (length / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            dispatch(String.format(Locale.ROOT,
                    "particle dust{color:[%s],scale:2.0} %.3f %.3f %.3f 0 0 0 0 1 force %s",
                    rgb, x1 + dx * t, y1 + dy * t, z1 + dz * t, player.getName()));
        }
    }

    private void dispatch(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }
}
