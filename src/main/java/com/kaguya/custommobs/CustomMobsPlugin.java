package com.kaguya.custommobs;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.manager.MobDeathListener;
import com.kaguya.custommobs.manager.MobManager;
import com.kaguya.custommobs.model.MobDefinition;
import com.kaguya.custommobs.pet.BlueprintLoader;
import com.kaguya.custommobs.pet.PetManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.logging.Level;

public class CustomMobsPlugin extends JavaPlugin {

    private MobManager mobManager;
    private PetDatabase petDatabase;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mobManager = new MobManager(this);
        mobManager.reloadDefinitions();
        mobManager.setBuildIntervalTicks(getConfig().getLong("pets.build-interval-ticks", 5));

        petDatabase = new PetDatabase(this);
        boolean dbReady = petDatabase.initialize();
        if (!dbReady) {
            getLogger().warning("ペット用データベースに接続できなかったため、テイム/建築機能は無効です(Mob自体の機能には影響しません)");
        } else {
            syncPetCatalog();
        }

        BlueprintLoader blueprintLoader = new BlueprintLoader(getLogger());
        PetManager petManager = new PetManager(this, mobManager, petDatabase,
                getConfig().getString("server-id", "mc1"), blueprintLoader);

        getServer().getPluginManager().registerEvents(new MobDeathListener(mobManager), this);
        getCommand("cmob").setExecutor(new CustomMobCommand(mobManager, petManager));

        // 1tickごとにAI Tick(重くなってきたら2~4tick間引き推奨)
        getServer().getScheduler().runTaskTimer(this, mobManager::tickAll, 1L, 1L);

        getLogger().info("CustomMobs 有効化完了");
    }

    /**
     * mobs.ymlのpet:セクションをcm_pet_catalogへ反映する。Webダッシュボード(fjew)の
     * ペットショップページはこのテーブルを見るだけなので、mobs.ymlを常に単一の真実の
     * ソースに保つため起動のたびに(非同期で)upsertする。
     */
    private void syncPetCatalog() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            for (MobDefinition def : mobManager.getAllDefinitions().values()) {
                if (def.getPet() == null) continue;
                try {
                    petDatabase.upsertCatalogEntry(def.getId(), def.getDisplayName(), def.getPet().getTameCost());
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "ペットカタログの同期に失敗しました (" + def.getId() + ")", e);
                }
            }
        });
    }

    @Override
    public void onDisable() {
        if (petDatabase != null) {
            petDatabase.shutdown();
        }
        getLogger().info("CustomMobs 無効化");
    }

    public MobManager getMobManager() {
        return mobManager;
    }
}
