package com.kaguya.custommobs.pet;

import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.Location;

import java.util.UUID;

/**
 * MobManagerが管理するインスタンスの状態変化をDB(cm_pets / cm_pet_build_jobs)に反映するための
 * フック。MobManagerはMob/ArmorStandそのものを担うが、DB永続化はPetDatabase/PetManagerの
 * 責務なので、ここで疎結合にしておく。
 */
public interface MobLifecycleListener {

    /** ブロックを1つ設置し、次のインデックスに進んだ直後に呼ばれる */
    void onBlockPlaced(UUID mobUuid, UUID ownerUuid, int listingId, Location origin, int nextIndex);

    /** 設計図の設置が全て完了したときに呼ばれる(進行状況の削除に使う) */
    void onBuildFinished(UUID mobUuid);

    /** 本体Mobが拾い直された直後に呼ばれる。中断中の建築ジョブがあれば再開する機会 */
    void onAdopted(CustomMobInstance instance);

    /**
     * 所有者ありのインスタンスが{@code MobManager#removeInstance}で取り除かれた直後に呼ばれる。
     * 死因は問わない(通常の死亡、{@code /killall}のような他プラグインによる直接除去、
     * {@code /cmob cleanup}での強制削除、チャンクアンロード等)。
     * <p>
     * これがないと、死亡時にDropを差し替えるだけの{@code MobDeathListener}側の処理では
     * カバーしきれないケース(特に{@code EntityDeathEvent}を経由せず{@code Entity#remove()}を
     * 直接呼ぶような外部プラグインのコマンド)で、cm_petsの所有権レコードが永久に残ってしまう。
     */
    void onInstanceRemoved(CustomMobInstance instance);
}
