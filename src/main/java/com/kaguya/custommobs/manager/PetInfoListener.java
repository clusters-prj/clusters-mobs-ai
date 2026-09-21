package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.gui.PetMenu;
import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
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
 *   <li>{@code PlayerInteractEvent}の{@code RIGHT_CLICK_AIR}/{@code RIGHT_CLICK_BLOCK} —
 *       どちらの箱にもヒットしない大きいモデルの場合。{@link MobManager#findNearestInView}の
 *       視線コーン検索で狙っている先を判定するフォールバック
 * </ul>
 * <p>
 * {@code RIGHT_CLICK_BLOCK}も見ているのは、視線の先にペットがいても、その手前(や奥)に
 * 地面・柵・看板などの何気ないブロックが1つでも視線上にあると、クライアントはそちらへの
 * ブロック対象クリックとして送ってしまい、空振り(AIR)にならないため。ペットより明らかに
 * 手前のブロックを触ろうとしている場合はそちらを優先し、ペットの方が近ければブロック側の
 * 操作をキャンセルしてメニューを開く。
 * <p>
 * 既知の制約: 狙っている方向のブロック到達距離(4.5〜6ブロック程度)以内に本当に何も
 * ブロックが無い場合、バニラクライアントは右クリックの操作パケット自体を一切送らない
 * (腕を振るアニメーションのみ)。この場合{@code PlayerInteractEvent}すら発火しないため、
 * ここでは検知しようがない。通常プレイ(ペットは地面近くにいる)では地面がブロックとして
 * 拾われるため問題にならないが、開けた空中を飛行中にペットへ向けて空振りした場合などは
 * メニューが開かないことがある(実機で確認済み)。
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
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        CustomMobInstance instance = mobManager.findNearestInView(player, RANGE, ANGLE_DEGREES);
        if (instance == null) return;

        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = event.getClickedBlock();
            if (clicked != null) {
                double blockDist = player.getEyeLocation().distance(clicked.getLocation().add(0.5, 0.5, 0.5));
                // 本体の生座標ではなく、y-offset/forward-offset/aim-forward-offset等の補正込みの
                // 狙い先(見た目の中心)で比較する。生座標のままだと、校正で狙い先を前方/上方に
                // ずらした分だけ実態とズレて「ブロックの方が近い」と誤判定し、ブロック側を優先して
                // メニューが開かなくなる(狙い先の方が実際は近いのに、ブロックを優先してしまう)
                double mobDist = player.getEyeLocation().distance(mobManager.getAimPoint(instance));
                // ブロックの方が明らかに近ければ、そちらを本当に触ろうとしている
                if (blockDist < mobDist - 1.0) return;
            }
            event.setCancelled(true); // 横取りしたので、チェスト等が開くのは防ぐ
        }

        petMenu.open(player, instance);
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
