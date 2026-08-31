package com.kaguya.custommobs;

import com.kaguya.custommobs.manager.MobDeathListener;
import com.kaguya.custommobs.manager.MobEntityLoadListener;
import com.kaguya.custommobs.manager.MobManager;
import com.kaguya.custommobs.manager.ModelStandGuardListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class CustomMobsPlugin extends JavaPlugin {

    private MobManager mobManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mobManager = new MobManager(this);
        mobManager.reloadDefinitions();

        getServer().getPluginManager().registerEvents(new MobDeathListener(mobManager), this);
        getServer().getPluginManager().registerEvents(new MobEntityLoadListener(this, mobManager), this);
        getServer().getPluginManager().registerEvents(new ModelStandGuardListener(mobManager), this);

        PluginCommand command = getCommand("cmob");
        if (command != null) {
            CustomMobCommand executor = new CustomMobCommand(mobManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
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
