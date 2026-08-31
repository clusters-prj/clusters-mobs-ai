package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.model.DropEntry;
import com.kaguya.custommobs.model.MobDefinition;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

public class MobDeathListener implements Listener {

    private final MobManager mobManager;

    public MobDeathListener(MobManager mobManager) {
        this.mobManager = mobManager;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String mobId = mobManager.getMobId(entity);
        if (mobId == null) return; // カスタムMobでなければ何もしない
        // モデル用ArmorStandにも同じmobIdタグを付けているので、本体だけを対象にする
        if (mobManager.isModelStand(entity)) return;

        MobDefinition def = mobManager.getDefinition(mobId);
        if (def == null) return;

        // バニラドロップを消して独自ドロップに差し替え
        event.getDrops().clear();
        event.setDroppedExp(0);

        for (DropEntry drop : def.getDrops()) {
            if (drop.getChance() <= 0.0) continue;
            if (ThreadLocalRandom.current().nextDouble() >= drop.getChance()) continue;

            int amount = drop.getAmountMin() == drop.getAmountMax()
                    ? drop.getAmountMin()
                    : ThreadLocalRandom.current().nextInt(drop.getAmountMin(), drop.getAmountMax() + 1);
            if (amount > 0) {
                event.getDrops().add(new ItemStack(drop.getItem(), amount));
            }
        }

        mobManager.removeInstance(entity.getUniqueId());
    }
}
