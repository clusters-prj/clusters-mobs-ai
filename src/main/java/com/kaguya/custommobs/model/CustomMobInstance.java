package com.kaguya.custommobs.model;

import com.kaguya.custommobs.pet.BuildJob;
import org.bukkit.entity.ArmorStand;
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
