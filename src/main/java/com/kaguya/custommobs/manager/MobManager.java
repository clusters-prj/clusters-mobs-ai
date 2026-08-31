package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.ai.AiBehavior;
import com.kaguya.custommobs.ai.MeleeAttackBehavior;
import com.kaguya.custommobs.model.AiBehaviorConfig;
import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.model.MobDefinition;
import com.kaguya.custommobs.model.ModelConfig;
import com.kaguya.custommobs.pet.Blueprint;
import com.kaguya.custommobs.pet.BuildJob;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class MobManager {

    private final JavaPlugin plugin;
    private final NamespacedKey mobIdKey;
    private final NamespacedKey ownerKey;
    private final Map<String, MobDefinition> definitions = new HashMap<>();
    private final Map<UUID, CustomMobInstance> activeMobs = new HashMap<>();
    private final Map<String, AiBehavior> behaviorRegistry = new HashMap<>();
    /** config.yml の pets.build-interval-ticks。デフォルト値はロード失敗時のフォールバック */
    private long buildIntervalTicks = 5;

    private long tickCounter = 0;

    public MobManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mobIdKey = new NamespacedKey(plugin, "custom_mob_id");
        this.ownerKey = new NamespacedKey(plugin, "pet_owner");
        registerDefaultBehaviors();
    }

    public void setBuildIntervalTicks(long ticks) {
        this.buildIntervalTicks = Math.max(1, ticks);
    }

    private void registerDefaultBehaviors() {
        behaviorRegistry.put("melee_attack", new MeleeAttackBehavior());
    }

    public void reloadDefinitions() {
        File file = new File(plugin.getDataFolder(), "mobs.yml");
        if (!file.exists()) {
            plugin.saveResource("mobs.yml", false);
        }
        definitions.clear();
        definitions.putAll(new MobDefinitionLoader(plugin.getLogger()).load(file));
    }

    public MobDefinition getDefinition(String id) {
        return definitions.get(id);
    }

    public Map<String, MobDefinition> getAllDefinitions() {
        return definitions;
    }

    public CustomMobInstance spawn(String mobId, Location location) {
        MobDefinition def = definitions.get(mobId);
        if (def == null) return null;

        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, def.getBaseEntity());

        // 表示名
        entity.customName(LegacyComponentSerializer.legacyAmpersand().deserialize(def.getDisplayName()));
        entity.setCustomNameVisible(true);

        // ステータス反映
        applyStats(entity, def);

        // タグ付け(死亡判定・データ復元用)
        entity.getPersistentDataContainer().set(mobIdKey, PersistentDataType.STRING, mobId);

        // バニラAIは切って独自Tickに完全移譲
        entity.setAI(false);

        CustomMobInstance instance = new CustomMobInstance(def, entity);

        if (def.getModel() != null) {
            entity.setInvisible(true);
            instance.setModelStand(spawnModelStand(entity, def.getModel()));
        }

        activeMobs.put(entity.getUniqueId(), instance);
        return instance;
    }

    /**
     * ArmorStandの手にカスタムアイテムを持たせて見た目を表現する。
     * 手持ちスロット以外はGeyser(Bedrock)に橋渡しされない。ItemDisplayも頭装備も
     * 統合版では一切描画されないことを実機で確認済み。markerも描画されないため使わない。
     * <p>
     * 腕ポーズもJava側モデルの display 変換も統合版には反映されないので、統合版での
     * 向き・サイズは bedrock-pack/ のアタッチャブルが決める。Java側の
     * display.thirdperson_righthand と値を揃えること。詳細は bedrock-pack/README.md 参照。
     */
    private ArmorStand spawnModelStand(LivingEntity base, ModelConfig model) {
        ArmorStand stand = (ArmorStand) base.getWorld().spawnEntity(base.getLocation(), EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.setGravity(false);
        stand.setPersistent(false);
        stand.setCustomNameVisible(false);

        // 右腕を前方水平に伸ばし、持たせたアイテムが水平に見えるようにする
        stand.setRightArmPose(new org.bukkit.util.EulerAngle(Math.toRadians(-90), 0, 0));

        ItemStack item = new ItemStack(model.getMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(model.getCustomModelData());
        item.setItemMeta(meta);
        stand.getEquipment().setItem(EquipmentSlot.HAND, item);

        return stand;
    }

    private void syncModelStand(CustomMobInstance instance) {
        ArmorStand stand = instance.getModelStand();
        if (stand == null || !stand.isValid()) return;

        LivingEntity entity = instance.getEntity();
        Location loc = entity.getLocation().clone();
        loc.setY(loc.getY() + instance.getDefinition().getModel().getYOffset());
        stand.teleport(loc);
    }

    private void applyStats(LivingEntity entity, MobDefinition def) {
        var stats = def.getStats();
        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(stats.getHealth());
            entity.setHealth(stats.getHealth());
        }
        if (entity.getAttribute(Attribute.GENERIC_ARMOR) != null) {
            entity.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(stats.getArmor());
        }
        if (entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(stats.getMovementSpeed());
        }
    }

    public String getMobId(LivingEntity entity) {
        return entity.getPersistentDataContainer().get(mobIdKey, PersistentDataType.STRING);
    }

    /** ペットの所有者を設定し、再起動をまたいでも読めるようPDCにも書く */
    public void setOwner(CustomMobInstance instance, UUID ownerUuid) {
        instance.setOwnerUuid(ownerUuid);
        instance.getEntity().getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, ownerUuid.toString());
    }

    public void clearOwner(CustomMobInstance instance) {
        instance.setOwnerUuid(null);
        instance.getEntity().getPersistentDataContainer().remove(ownerKey);
    }

    /** BukkitSchedulerから毎Tick呼ばれる想定 */
    public void tickAll() {
        tickCounter++;
        Iterator<Map.Entry<UUID, CustomMobInstance>> it = activeMobs.entrySet().iterator();
        while (it.hasNext()) {
            CustomMobInstance instance = it.next().getValue();
            LivingEntity entity = instance.getEntity();

            if (!entity.isValid() || entity.isDead()) {
                if (instance.getModelStand() != null) {
                    instance.getModelStand().remove();
                }
                it.remove();
                continue;
            }

            if (instance.getModelStand() != null) {
                syncModelStand(instance);
            }

            for (AiBehaviorConfig behaviorConfig : instance.getDefinition().getAiBehaviors()) {
                AiBehavior behavior = behaviorRegistry.get(behaviorConfig.getType());
                if (behavior != null) {
                    behavior.tick(instance, behaviorConfig, tickCounter);
                }
            }

            if (instance.getActiveBuild() != null) {
                processBuildJob(instance);
            }
        }
    }

    /**
     * 建築ジョブを1Tick分進める。mobs.ymlの静的なaiリストとは別に、
     * /cmob build コマンドで動的に割り当てられたジョブをここで直接処理する
     * (ブループリントは個体ごと・実行時に決まるためAiBehaviorレジストリには乗せない)。
     * <p>
     * v1につき移動は単純テレポート(障害物回避なし)。設置先の1つ上に立たせるだけ。
     */
    private void processBuildJob(CustomMobInstance instance) {
        BuildJob job = instance.getActiveBuild();
        if (!job.isReady(tickCounter, buildIntervalTicks)) return;

        Blueprint blueprint = job.getBlueprint();
        var blocks = blueprint.getBlocks();
        int index = job.getNextIndex();
        if (index >= blocks.size()) {
            instance.setActiveBuild(null);
            return;
        }

        var entry = blocks.get(index);
        Location origin = job.getOrigin();
        Location target = origin.clone().add(entry.getX(), entry.getY(), entry.getZ());

        Material material;
        try {
            material = Material.valueOf(entry.getMaterial());
        } catch (IllegalArgumentException ex) {
            // BlueprintLoaderで弾いているはずだが、念のため
            job.advance();
            return;
        }

        LivingEntity self = instance.getEntity();
        self.teleport(target.clone().add(0, 1, 0));

        Block block = target.getBlock();
        block.setType(material);
        target.getWorld().playSound(target, Sound.BLOCK_STONE_PLACE, 0.6f, 1.0f);

        job.markPlaced(tickCounter);
        job.advance();

        if (job.isDone()) {
            instance.setActiveBuild(null);
            UUID ownerUuid = instance.getOwnerUuid();
            if (ownerUuid != null) {
                var owner = plugin.getServer().getPlayer(ownerUuid);
                if (owner != null) {
                    owner.sendMessage("§a" + instance.getDefinition().getDisplayName()
                            + " §aが設計図「" + blueprint.getName() + "」の建築を完了しました");
                }
            }
        }
    }

    public CustomMobInstance getInstance(UUID entityId) {
        return activeMobs.get(entityId);
    }

    public void removeInstance(UUID entityId) {
        CustomMobInstance instance = activeMobs.remove(entityId);
        if (instance != null && instance.getModelStand() != null) {
            instance.getModelStand().remove();
        }
    }
}
