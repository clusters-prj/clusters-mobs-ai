package com.kaguya.custommobs.model;

import com.kaguya.custommobs.pet.BuildJob;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CustomMobInstance {
    private final MobDefinition definition;
    private final LivingEntity entity;
    // AI behaviorごとのクールダウン管理などに使う汎用ステート置き場
    private final Map<String, Long> cooldowns = new HashMap<>();
    private ArmorStand modelStand;
    /**
     * 右クリック判定専用の当たり判定エンティティ(Interaction, 1.19.4〜)。
     * モデル用ArmorStandの実際の当たり判定は元のMobサイズの小さい箱のままで見た目と合わないため、
     * 見た目の中心(MobManager#getAimPoint)に合わせて配置した、任意サイズを指定できる専用の
     * 当たり判定を別途持たせている。これはブロックの有無に関係なくエンティティとして
     * クリックを拾えるため、空中に浮くペット(近くにブロックが無く、視線コーン検索の
     * フォールバック=RIGHT_CLICK_AIRすら発火しないバニラ制約に引っかかるケース)でも
     * 確実にクリックを検知できる
     */
    private Interaction interactionHitbox;
    // ペットの所有者。null なら未所有
    private UUID ownerUuid;
    // 実行中の建築ジョブ。mobs.ymlの静的なaiリストとは別に、コマンドで動的に割り当てる
    private BuildJob activeBuild;
    // 所有者への追従ON/OFF。メモリ上のみの状態(再起動で解除されても致命的ではないため永続化しない)
    private boolean followingOwner = false;

    public CustomMobInstance(MobDefinition definition, LivingEntity entity) {
        this.definition = definition;
        this.entity = entity;
    }

    public MobDefinition getDefinition() { return definition; }
    public LivingEntity getEntity() { return entity; }

    public ArmorStand getModelStand() { return modelStand; }
    public void setModelStand(ArmorStand modelStand) { this.modelStand = modelStand; }

    public Interaction getInteractionHitbox() { return interactionHitbox; }
    public void setInteractionHitbox(Interaction interactionHitbox) { this.interactionHitbox = interactionHitbox; }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }
    public boolean isOwnedBy(UUID uuid) { return ownerUuid != null && ownerUuid.equals(uuid); }

    public BuildJob getActiveBuild() { return activeBuild; }
    public void setActiveBuild(BuildJob activeBuild) { this.activeBuild = activeBuild; }

    public boolean isFollowingOwner() { return followingOwner; }
    public void setFollowingOwner(boolean followingOwner) { this.followingOwner = followingOwner; }

    public boolean isReady(String key, long cooldownTicks, long nowTick) {
        Long last = cooldowns.get(key);
        return last == null || (nowTick - last) >= cooldownTicks;
    }

    public void markUsed(String key, long nowTick) {
        cooldowns.put(key, nowTick);
    }
}
