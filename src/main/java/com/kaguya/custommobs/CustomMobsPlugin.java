package com.kaguya.custommobs;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.manager.MobDeathListener;
import com.kaguya.custommobs.manager.MobEntityLoadListener;
import com.kaguya.custommobs.manager.MobManager;
import com.kaguya.custommobs.manager.ModelStandGuardListener;
import com.kaguya.custommobs.pet.BlueprintLoader;
import com.kaguya.custommobs.pet.PetManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

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
        }

        BlueprintLoader blueprintLoader = new BlueprintLoader(getLogger());
        PetManager petManager = new PetManager(this, mobManager, petDatabase,
                getConfig().getString("server-id", "mc1"), blueprintLoader);
        petManager.syncCatalog();

        getServer().getPluginManager().registerEvents(new MobDeathListener(mobManager), this);
        getServer().getPluginManager().registerEvents(new MobEntityLoadListener(this, mobManager), this);
        getServer().getPluginManager().registerEvents(new ModelStandGuardListener(mobManager), this);

        PluginCommand command = getCommand("cmob");
        if (command != null) {
            command.setExecutor(new CustomMobCommand(mobManager, petManager));
        } else {
            getLogger().warning("plugin.yml に cmob コマンドが定義されていません");
        }

        // /reload や再有効化のときは、すでにワールドにいるカスタムMobを拾い直す
        int adopted = mobManager.adoptLoadedEntities();
        if (adopted > 0) {
            getLogger().info("既存のカスタムMobを復帰させました: " + adopted + "体");
        }

        // 1tickごとにAI Tick(重くなってきたら2~4tick間引き推奨)
        getServer().getScheduler().runTaskTimer(this, mobManager::tickAll, 1L, 1L);

        getLogger().info("CustomMobs 有効化完了");
    }

    @Override
    public void onDisable() {
        if (petDatabase != null) {
            petDatabase.shutdown();
        }
        if (mobManager != null) {
            // モデル用ArmorStandを残すと、再有効化時に二重に出る
            mobManager.shutdown();
        }
        getLogger().info("CustomMobs 無効化");
    }

    public MobManager getMobManager() {
        return mobManager;
    }
}
