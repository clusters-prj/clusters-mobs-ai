package com.kaguya.custommobs.pet;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.manager.MobManager;
import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.model.PetConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/**
 * ペットのテイム・解放を担当する。DB書き込みは非同期、Mob/PDCへの反映はメインスレッドで行う
 * (Bukkit APIはメインスレッド以外から呼べないため)。
 */
public class PetManager {

    private final JavaPlugin plugin;
    private final MobManager mobManager;
    private final PetDatabase database;
    private final String serverId;
    private final BlueprintLoader blueprintLoader;

    public PetManager(JavaPlugin plugin, MobManager mobManager, PetDatabase database, String serverId,
                       BlueprintLoader blueprintLoader) {
        this.plugin = plugin;
        this.mobManager = mobManager;
        this.database = database;
        this.serverId = serverId;
        this.blueprintLoader = blueprintLoader;
    }

    /** 視線上の自分のペットに設計図を割り当てる。原点はプレイヤーの現在地 */
    public void assignBuild(Player player, CustomMobInstance target, String blueprintName) {
        if (!target.isOwnedBy(player.getUniqueId()) && !player.hasPermission("custommobs.command")) {
            player.sendMessage("§c自分のペットではありません");
            return;
        }
        Blueprint blueprint = blueprintLoader.load(blueprintName);
        if (blueprint == null) {
            player.sendMessage("§c設計図が読み込めませんでした: " + blueprintName);
            return;
        }
        if (blueprint.getBlocks().isEmpty()) {
            player.sendMessage("§c設計図に有効なブロックがありません: " + blueprintName);
            return;
        }

        Location origin = player.getLocation();
        target.setActiveBuild(new BuildJob(blueprint, origin));
        player.sendMessage("§a" + target.getDefinition().getDisplayName()
                + " §aに設計図「" + blueprint.getName() + "」を割り当てました(" + blueprint.getBlocks().size() + "ブロック)");
    }

    public boolean isDatabaseReady() {
        return database.isReady();
    }

    public void tame(Player player, CustomMobInstance target) {
        PetConfig petConfig = target.getDefinition().getPet();
        if (petConfig == null) {
            player.sendMessage("§cこのMobはペットにできません");
            return;
        }
        if (target.getOwnerUuid() != null) {
            player.sendMessage("§cすでに誰かに飼われています");
            return;
        }
        if (!database.isReady()) {
            player.sendMessage("§cペット用データベースに接続できていないため、今はテイムできません");
            return;
        }

        long cost = petConfig.getTameCost();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean charged;
            try {
                charged = database.tryCharge(player.getUniqueId(), cost);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "残高消費に失敗しました", e);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§cデータベースエラーによりテイムに失敗しました"));
                return;
            }

            if (!charged) {
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§c残高が足りません(必要: " + cost + ")"));
                return;
            }

            try {
                database.insertPet(target.getEntity().getUniqueId(), player.getUniqueId(),
                        target.getDefinition().getId(), serverId, target.getDefinition().getDisplayName());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "ペット登録に失敗しました(残高は消費済み)", e);
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!target.getEntity().isValid()) return; // その間に死亡/デスポーンした場合
                mobManager.setOwner(target, player.getUniqueId());
                player.sendMessage("§a" + target.getDefinition().getDisplayName() + " §aをテイムしました(§e" + cost + "§a 消費)");
            });
        });
    }

    public void release(Player player, CustomMobInstance target) {
        if (!target.isOwnedBy(player.getUniqueId()) && !player.hasPermission("custommobs.command")) {
            player.sendMessage("§c自分のペットではありません");
            return;
        }

        var mobUuid = target.getEntity().getUniqueId();
        mobManager.clearOwner(target);
        target.setActiveBuild(null);
        player.sendMessage("§a" + target.getDefinition().getDisplayName() + " §aを手放しました");

        if (!database.isReady()) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                database.deletePet(mobUuid);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "ペット解除の記録に失敗しました", e);
            }
        });
    }

    /** /cmob mypets 用。DB問い合わせなので呼び出し側で非同期化すること */
    public List<PetDatabase.PetRecord> listOwned(java.util.UUID ownerUuid) throws SQLException {
        return database.listByOwner(ownerUuid);
    }
}
