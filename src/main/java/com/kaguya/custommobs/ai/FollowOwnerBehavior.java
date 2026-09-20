package com.kaguya.custommobs.ai;

import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 所有者への追従。mobs.ymlの静的なaiリストに乗る種族固有ビヘイビア({@link AiBehavior})
 * とは違い、{@code CustomMobInstance#isFollowingOwner()}という個体ごとに動的にON/OFFされる
 * 状態を見るだけなので、{@link com.kaguya.custommobs.pet.BuildJob}と同じく
 * {@code MobManager#tickAll()}から直接呼び出す。
 */
public class FollowOwnerBehavior {

    /** これより近ければ追従移動しない(所有者にめり込まないように少し距離を空ける) */
    private static final double STOP_DISTANCE = 3.0;
    /** これより離れていたら歩いて追いつくのを諦め、所有者の近くへ瞬間移動する */
    private static final double TELEPORT_DISTANCE = 24.0;
    /** 移動速度attributeが取れなかったときのフォールバック */
    private static final double FALLBACK_SPEED = 0.25;

    public void tick(CustomMobInstance instance, long nowTick) {
        if (!instance.isFollowingOwner()) return;

        UUID ownerUuid = instance.getOwnerUuid();
        if (ownerUuid == null) return;

        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner == null || !owner.isOnline() || owner.isDead()) return;

        LivingEntity self = instance.getEntity();
        Location ownerLoc = owner.getLocation();
        if (!self.getWorld().equals(ownerLoc.getWorld())) return;

        double distance = self.getLocation().distance(ownerLoc);
        if (distance <= STOP_DISTANCE) return;

        if (distance > TELEPORT_DISTANCE) {
            self.teleport(ownerLoc);
            return;
        }

        TeleportMovement.moveToward(self, ownerLoc, movementSpeed(self) * 5.0);
    }

    private double movementSpeed(LivingEntity self) {
        AttributeInstance attr = self.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        return attr == null ? FALLBACK_SPEED : attr.getValue();
    }
}
