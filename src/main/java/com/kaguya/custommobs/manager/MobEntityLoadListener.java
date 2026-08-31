package com.kaguya.custommobs.manager;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * チャンク(のエンティティ)が読み込まれたときに、PDCタグの付いたカスタムMobを
 * MobManager に拾い直させる。
 * <p>
 * activeMobs はメモリ上にしかないため、これがないとチャンクの読み直しやサーバー再起動で
 * 「透明でAIも効かない置物」が残ってしまう。1.17以降エンティティはチャンクとは別に
 * 読み込まれるので、ChunkLoadEvent ではなく EntitiesLoadEvent を使う必要がある。
 * <p>
 * 拾い直しではモデル用ArmorStandをspawnするが、エンティティ読み込みの最中に
 * spawnEntityを呼ぶのは避けたいので、1tick遅らせて実行する。
 */
public class MobEntityLoadListener implements Listener {

    private final JavaPlugin plugin;
    private final MobManager mobManager;

    public MobEntityLoadListener(JavaPlugin plugin, MobManager mobManager) {
        this.plugin = plugin;
        this.mobManager = mobManager;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<Entity> entities = List.copyOf(event.getEntities());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Entity entity : entities) {
                mobManager.adopt(entity);
            }
        });
    }
}
