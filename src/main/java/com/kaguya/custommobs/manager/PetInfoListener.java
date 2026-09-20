package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.gui.PetMenu;
import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * カスタムMobに向かって右クリックすると、所有者・HP・種別などを表示するペットメニューGUIを開く。
 * <p>
 * モデルの見た目は巨大でも、実際の当たり判定(本体・モデル用ArmorStandとも)は元のサイズの
 * 小さい箱のままなので、見た目の中心を狙ったクリックが実際の箱にヒットするかどうかは
 * モデルの大きさ次第で変わる。狙っている先がその小さい箱に入っているときは、クライアントは
 * 空振りではなくエンティティ対象のクリックとして送るため、実際には3種類のイベントを
 * 拾い分ける必要がある(当初は{@code RIGHT_CLICK_AIR}だけで済むと思っていたが、
 * 元のMobサイズに近いモデルではほぼ確実に実際の箱を直接ヒットしてしまい、
 * それらのイベントを無視していたぶん表示されていなかった):
 * <ul>
 *   <li>{@link PlayerArmorStandManipulateEvent} — モデル用ArmorStandの箱に直接ヒットした場合
 *   <li>{@link PlayerInteractEntityEvent} — 本体(の元のMobサイズの箱)に直接ヒットした場合
 *   <li>{@code PlayerInteractEvent}の{@code RIGHT_CLICK_AIR} — どちらの箱にもヒットしない
 *       大きいモデルの場合。{@link MobManager#findNearestInView}の視線コーン検索で狙っている
 *       先を判定するフォールバック
 * </ul>
 */
public class PetInfoListener implements Listener {

    /** 情報表示として「狙っている」とみなす最大距離(ブロック) */
    private static final double RANGE = 8.0;
    /** 視線からこの角度(度)以内。右クリックは至近距離での使用が主なので、CustomMobCommandの
     * 角度(25度)よりさらに余裕を持たせておく */
    private static final double ANGLE_DEGREES = 30.0;

    private final MobManager mobManager;
    private final PetMenu petMenu;

    public PetInfoListener(MobManager mobManager, PetMenu petMenu) {
        this.mobManager = mobManager;
        this.petMenu = petMenu;
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

        petMenu.open(event.getPlayer(), instance);
    }

    /** モデル用ArmorStandの箱を直接右クリックした場合。ModelStandGuardListenerが操作自体は
     * 別途キャンセルするので、ここではメニューを開くだけを行う */
    @EventHandler
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        CustomMobInstance instance = mobManager.getInstance(event.getRightClicked());
        if (instance == null) return;

        petMenu.open(event.getPlayer(), instance);
    }

    /** 本体(元のMobサイズの箱)を直接右クリックした場合。ArmorStand相手はPlayerArmorStandManipulateEvent
     * 側で別途処理するので、二重表示を避けるためここでは除外する */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getRightClicked() instanceof ArmorStand) return;

        CustomMobInstance instance = mobManager.getInstance(event.getRightClicked());
        if (instance == null) return;

        petMenu.open(event.getPlayer(), instance);
    }
}
