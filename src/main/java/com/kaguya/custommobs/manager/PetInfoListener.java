package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.model.StatBlock;
import com.kaguya.custommobs.pet.BuildJob;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

/**
 * カスタムMobを右クリックすると、所有者・HP・種別などを表示する。
 * <p>
 * 右クリック先は本体(見えない)かモデル用ArmorStand(見た目)のどちらの可能性もあるため、
 * 解決は{@link MobManager#getInstance(org.bukkit.entity.Entity)}に任せる。
 * 情報表示は読み取り専用なので、誰の所有かに関わらず誰でも見られるようにしている
 * (どのMobが誰のペットか分かった方が、他人のペットを誤って攻撃/操作するのを防げる)。
 */
public class PetInfoListener implements Listener {

    private final MobManager mobManager;

    public PetInfoListener(MobManager mobManager) {
        this.mobManager = mobManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        // メインハンド分だけ処理する(オフハンド分も別イベントとして発火し、二重表示になるため)
        if (event.getHand() != EquipmentSlot.HAND) return;

        CustomMobInstance instance = mobManager.getInstance(event.getRightClicked());
        if (instance == null) return;

        event.setCancelled(true); // バニラの騎乗・リード等の副作用を防ぐ
        showInfo(event.getPlayer(), instance);
    }

    private void showInfo(Player player, CustomMobInstance instance) {
        LivingEntity entity = instance.getEntity();
        StatBlock stats = instance.getDefinition().getStats();

        player.sendMessage("§e--- " + instance.getDefinition().getDisplayName() + " §e---");
        player.sendMessage("§7種別: §f" + instance.getDefinition().getId());
        player.sendMessage(String.format(Locale.ROOT, "§7HP: §f%.1f / %.1f", entity.getHealth(), stats.getHealth()));

        var ownerUuid = instance.getOwnerUuid();
        if (ownerUuid == null) {
            player.sendMessage("§7所有者: §8未所有");
        } else {
            String ownerName = Bukkit.getOfflinePlayer(ownerUuid).getName();
            player.sendMessage("§7所有者: §f" + (ownerName != null ? ownerName : ownerUuid));
        }

        BuildJob job = instance.getActiveBuild();
        if (job != null) {
            player.sendMessage("§7建築中: §f" + job.getNextIndex() + " / " + job.getBlueprint().getBlocks().size()
                    + " §7(設計図「" + job.getBlueprint().getName() + "」)");
        }
    }
}
