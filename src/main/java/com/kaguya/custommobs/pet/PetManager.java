package com.kaguya.custommobs.pet;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.manager.MobManager;
import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.model.MobDefinition;
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

    /**
     * 視線上の自分のペットに、マーケットプレイスで購入済みの設計図を割り当てる。
     * 原点はプレイヤーの現在地。所有権チェックはDB問い合わせなので非同期で行う。
     */
    public void assignBuild(Player player, CustomMobInstance target, int listingId) {
        if (!target.isOwnedBy(player.getUniqueId()) && !player.hasPermission("custommobs.command")) {
            player.sendMessage("§c自分のペットではありません");
            return;
        }
        if (!database.isReady()) {
            player.sendMessage("§cペット用データベースに接続できていないため、今は建築を割り当てられません");
            return;
        }

        Location origin = player.getLocation();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PetDatabase.BlueprintRow row;
            try {
                row = database.findOwnedBlueprintJson(player.getUniqueId(), listingId);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "設計図の取得に失敗しました", e);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§cデータベースエラーにより設計図を取得できませんでした"));
                return;
            }
            if (row == null) {
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§cその設計図(ID: " + listingId + ")を所有していません"));
                return;
            }

            Blueprint blueprint = blueprintLoader.parse(row.json(), row.title());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!target.getEntity().isValid()) return;
                if (blueprint == null) {
                    player.sendMessage("§c設計図の読み込みに失敗しました: " + row.title());
                    return;
                }
                if (blueprint.getBlocks().isEmpty()) {
                    player.sendMessage("§c設計図に有効なブロックがありません: " + row.title());
                    return;
                }
                target.setActiveBuild(new BuildJob(blueprint, origin));
                player.sendMessage("§a" + target.getDefinition().getDisplayName()
                        + " §aに設計図「" + blueprint.getName() + "」を割り当てました(" + blueprint.getBlocks().size() + "ブロック)");
            });
        });
    }

    /** Webで購入したペットを受け取る。所有権はDBのcm_pet_claimsで管理しているので非同期で確認する */
    public void claim(Player player) {
        if (!database.isReady()) {
            player.sendMessage("§cペット用データベースに接続できていないため、今は受け取れません");
            return;
        }

        Location spawnLoc = player.getLocation();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PetDatabase.PetClaim> claims;
            try {
                claims = database.listUnclaimed(player.getUniqueId());
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "受け取り待ちペットの取得に失敗しました", e);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§cデータベースエラーにより受け取りに失敗しました"));
                return;
            }
            if (claims.isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§e受け取り待ちのペットはありません"));
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (PetDatabase.PetClaim claim : claims) {
                    CustomMobInstance instance = mobManager.spawn(claim.mobType(), spawnLoc);
                    if (instance == null) {
                        player.sendMessage("§cペットのスポーンに失敗しました(定義なし): " + claim.mobType());
                        continue;
                    }
                    mobManager.setOwner(instance, player.getUniqueId());
                    player.sendMessage("§a" + instance.getDefinition().getDisplayName() + " §aを受け取りました");

                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            database.insertPet(instance.getEntity().getUniqueId(), player.getUniqueId(),
                                    claim.mobType(), serverId, instance.getDefinition().getDisplayName());
                            database.markClaimed(claim.id());
                        } catch (SQLException e) {
                            plugin.getLogger().log(Level.WARNING, "受け取り記録に失敗しました", e);
                        }
                    });
                }
            });
        });
    }

    public boolean isDatabaseReady() {
        return database.isReady();
    }

    /**
     * mobs.ymlのpet:セクションをcm_pet_catalogへ反映する。Webダッシュボード(fjew)の
     * ペットショップページはこのテーブルを見るだけなので、mobs.ymlを常に単一の真実の
     * ソースに保つため、プラグイン起動時と /cmob reload のたびに(非同期で)upsertする。
     */
    public void syncCatalog() {
        if (!database.isReady()) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (MobDefinition def : mobManager.getAllDefinitions().values()) {
                if (def.getPet() == null) continue;
                try {
                    database.upsertCatalogEntry(def.getId(), def.getDisplayName(), def.getPet().getTameCost());
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "ペットカタログの同期に失敗しました (" + def.getId() + ")", e);
                }
            }
        });
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

    /** /cmob mypets 用。DB問い合わせを非同期で行い、結果をメインスレッドでcallbackに渡す */
    public void listOwnedAsync(java.util.UUID ownerUuid, PetListCallback callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PetDatabase.PetRecord> pets = null;
            SQLException error = null;
            try {
                pets = database.listByOwner(ownerUuid);
            } catch (SQLException e) {
                error = e;
                plugin.getLogger().log(Level.WARNING, "所有ペット一覧の取得に失敗しました", e);
            }
            List<PetDatabase.PetRecord> finalPets = pets != null ? pets : List.of();
            SQLException finalError = error;
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.onResult(finalPets, finalError));
        });
    }

    public interface PetListCallback {
        void onResult(List<PetDatabase.PetRecord> pets, SQLException error);
    }
}
