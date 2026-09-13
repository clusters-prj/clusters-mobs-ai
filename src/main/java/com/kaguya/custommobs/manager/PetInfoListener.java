package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.model.StatBlock;
import com.kaguya.custommobs.pet.BuildJob;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

/**
 * カスタムMobに向かって右クリックすると、所有者・HP・種別などを表示する。
 * <p>
 * {@code PlayerInteractEntityEvent}(実際にクリックしたエンティティを渡すイベント)は
 * 使わない。あれはクライアント側で実際にエンティティの当たり判定にヒットしないと
 * そもそも発火しないが、見た目上の巨大なモデルはリソースパック側でアイテムを引き伸ばして
 * 描画しているだけで、実際の当たり判定(本体・モデル用ArmorStandとも)は元のサイズの
 * 小さい箱のままなので、見た目の中心を狙ってもクライアントの時点でヒットせず、
 * イベント自体が発火しない({@code /cmob build}等のコマンドが正確なレイキャストでは
 * 使えなかったのと同じ理由)。代わりに、素手での空振り右クリック({@code PlayerInteractEvent}
 * の{@code RIGHT_CLICK_AIR})をフックし、{@link MobManager#findNearestInView}と同じ
 * 視線コーン検索で狙っている先を判定する。
 */
public class PetInfoListener implements Listener {

    /** 情報表示として「狙っている」とみなす最大距離(ブロック) */
    private static final double RANGE = 8.0;
    /** 視線からこの角度(度)以内。右クリックは至近距離での使用が主なので、CustomMobCommandの
     * 角度(25度)よりさらに余裕を持たせておく */
    private static final double ANGLE_DEGREES = 30.0;

    private final MobManager mobManager;

    public PetInfoListener(MobManager mobManager) {
        this.mobManager = mobManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // メインハンド分だけ処理する(オフハンド分も別イベントとして発火し、二重表示になるため)
        if (event.getHand() != EquipmentSlot.HAND) return;
        // ブロックが手前にあると別の操作(チェストを開く等)である可能性が高いので、
        // 何もない方向への空振り右クリックだけを対象にする
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

        CustomMobInstance instance = mobManager.findNearestInView(event.getPlayer(), RANGE, ANGLE_DEGREES);
        if (instance == null) return;

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
