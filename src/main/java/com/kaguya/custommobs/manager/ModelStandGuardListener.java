package com.kaguya.custommobs.manager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;

/**
 * モデル用ArmorStandはバニラの「右クリックで手持ちアイテムを入れ替える」操作を
 * 受け付けてしまう。プレイヤーが何か持った状態でモデルStandを右クリックすると、
 * カスタムモデルのアイテムがプレイヤーの手/インベントリに渡り、代わりに持っていた
 * アイテムがStandの手に収まってしまう(見た目が壊れる)ため、モデルStand相手の
 * 操作は無条件でキャンセルする。
 */
public class ModelStandGuardListener implements Listener {

    private final MobManager mobManager;

    public ModelStandGuardListener(MobManager mobManager) {
        this.mobManager = mobManager;
    }

    @EventHandler
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (mobManager.isModelStand(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }
}
