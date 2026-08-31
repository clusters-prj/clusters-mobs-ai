package com.kaguya.custommobs.pet;

import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.Location;

import java.util.UUID;

/**
 * 建築ジョブの進行をDB(cm_pet_build_jobs)に反映するためのフック。
 * <p>
 * MobManagerはブロック設置そのものを担うが、DB永続化はPetDatabase/PetManagerの責務なので、
 * ここで疎結合にしておく。チャンクアンロード後の「拾い直し」でも続きから建築を再開できるように、
 * 本体Mobが拾い直された(adoptされた)タイミングも通知する。
 */
public interface BuildProgressListener {

    /** ブロックを1つ設置し、次のインデックスに進んだ直後に呼ばれる */
    void onBlockPlaced(UUID mobUuid, UUID ownerUuid, int listingId, Location origin, int nextIndex);

    /** 設計図の設置が全て完了したときに呼ばれる(進行状況の削除に使う) */
    void onBuildFinished(UUID mobUuid);

    /** 本体Mobが拾い直された直後に呼ばれる。中断中の建築ジョブがあれば再開する機会 */
    void onAdopted(CustomMobInstance instance);
}
