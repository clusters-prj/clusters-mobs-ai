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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class MobManager {

    /** 位置がほぼ同じかを判定するときの許容誤差 */
    private static final double SYNC_EPSILON = 1.0E-4;

    private final JavaPlugin plugin;
    private final NamespacedKey mobIdKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey standOwnerKey;
    private final Map<String, MobDefinition> definitions = new LinkedHashMap<>();
    private final Map<UUID, CustomMobInstance> activeMobs = new HashMap<>();
    private final Map<String, AiBehavior> behaviorRegistry = new HashMap<>();
    /** 同じ例外でログを埋め尽くさないための既出フラグ */
    private final Set<String> loggedBehaviorFailures = new HashSet<>();
    /** config.yml の pets.build-interval-ticks。デフォルト値はロード失敗時のフォールバック */
    private long buildIntervalTicks = 5;

    private long tickCounter = 0;

    public MobManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mobIdKey = new NamespacedKey(plugin, "custom_mob_id");
        this.ownerKey = new NamespacedKey(plugin, "pet_owner");
        this.standOwnerKey = new NamespacedKey(plugin, "custom_mob_owner");
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
        warnUnknownBehaviors();
    }

    /** YAMLに書かれているのに実装が登録されていないAIタイプは黙って無視されるので、ロード時に気づけるようにする */
    private void warnUnknownBehaviors() {
        for (MobDefinition def : definitions.values()) {
            for (AiBehaviorConfig behavior : def.getAiBehaviors()) {
                if (!behaviorRegistry.containsKey(behavior.getType())) {
                    plugin.getLogger().warning("未登録のAIタイプなので無視されます (" + def.getId() + "): " + behavior.getType());
                }
            }
        }
    }

    public MobDefinition getDefinition(String id) {
        return definitions.get(id);
    }

    public Map<String, MobDefinition> getAllDefinitions() {
        return Collections.unmodifiableMap(definitions);
    }

    public CustomMobInstance spawn(String mobId, Location location) {
        MobDefinition def = definitions.get(mobId);
        if (def == null) return null;

        World world = location.getWorld();
        if (world == null) return null;

        Entity spawned = world.spawnEntity(location, def.getBaseEntity());
        if (!(spawned instanceof LivingEntity entity)) {
            spawned.remove();
            plugin.getLogger().warning("base-entityがLivingEntityではありません: " + mobId + " (" + def.getBaseEntity() + ")");
            return null;
        }

        // 表示名
        entity.customName(LegacyComponentSerializer.legacyAmpersand().deserialize(def.getDisplayName()));
        entity.setCustomNameVisible(true);

        // base-entity(ゾンビ等)本来の鳴き声・足音を出さないようにする
        entity.setSilent(true);

        // ステータス反映
        applyStats(entity, def);

        // タグ付け(死亡判定・再読み込み時の拾い直し用)
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
     * すでにワールドにいるカスタムMobを activeMobs に拾い直す。
     * <p>
     * activeMobs はメモリ上にしかないので、サーバー再起動やチャンクの読み直しをすると
     * 本体Mob(setInvisibleとPDCタグはNBTとして永続化される)だけが「透明でAIも効かない置物」
     * として残ってしまう。EntitiesLoadEvent と onEnable からここを通して復帰させる。
     *
     * @return 拾い直したインスタンス。対象外だった場合は null
     */
    public CustomMobInstance adopt(Entity candidate) {
        if (!(candidate instanceof LivingEntity entity)) return null;
        if (candidate instanceof ArmorStand) return null; // モデル用Standは本体ではない
        if (!entity.isValid()) return null; // 1tick遅れで呼ばれるので、その間に消えている可能性がある
        if (activeMobs.containsKey(entity.getUniqueId())) return null;

        String mobId = getMobId(entity);
        if (mobId == null) return null;

        MobDefinition def = definitions.get(mobId);
        if (def == null) {
            plugin.getLogger().warning("定義が見つからないカスタムMobが残っています: " + mobId
                    + " (/cmob cleanup " + mobId + " で掃除できます)");
            return null;
        }

        // ステータスはNBT側に永続化済みなので上書きしない(HPが全回復してしまうため)
        entity.setAI(false);
        entity.setSilent(true);

        CustomMobInstance instance = new CustomMobInstance(def, entity);
        if (def.getModel() != null) {
            entity.setInvisible(true);
            ArmorStand existing = findOwnedStand(entity);
            instance.setModelStand(existing != null ? existing : spawnModelStand(entity, def.getModel()));
        }

        activeMobs.put(entity.getUniqueId(), instance);
        return instance;
    }

    /** 読み込み済みの全ワールドを走査して拾い直す。戻り値は拾えた数 */
    public int adoptLoadedEntities() {
        int adopted = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (adopt(entity) != null) adopted++;
            }
        }
        return adopted;
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
        Location standLoc = base.getLocation().add(0, model.getYOffset(), 0);
        ArmorStand stand = (ArmorStand) base.getWorld().spawnEntity(standLoc, EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.setGravity(false);
        stand.setPersistent(false);
        stand.setCustomNameVisible(false);
        stand.setInvulnerable(true);
        stand.setCollidable(false);

        // 本体が消えたあとに取り残されたStandを特定できるようにしておく
        PersistentDataContainer pdc = stand.getPersistentDataContainer();
        pdc.set(standOwnerKey, PersistentDataType.STRING, base.getUniqueId().toString());
        String mobId = getMobId(base);
        if (mobId != null) {
            pdc.set(mobIdKey, PersistentDataType.STRING, mobId);
        }

        // 右腕を前方水平に伸ばし、持たせたアイテムが水平に見えるようにする
        stand.setRightArmPose(new org.bukkit.util.EulerAngle(Math.toRadians(-90), 0, 0));

        ItemStack item = new ItemStack(model.getMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(model.getCustomModelData());
            item.setItemMeta(meta);
        }
        stand.getEquipment().setItem(EquipmentSlot.HAND, item);

        return stand;
    }

    /** 本体の近くにいる、自分が持ち主のモデル用Standを探す(重複していれば余りを消す) */
    private ArmorStand findOwnedStand(LivingEntity base) {
        String uuid = base.getUniqueId().toString();
        ArmorStand found = null;
        for (Entity nearby : base.getNearbyEntities(2.0, 4.0, 2.0)) {
            if (!(nearby instanceof ArmorStand stand)) continue;
            String owner = stand.getPersistentDataContainer().get(standOwnerKey, PersistentDataType.STRING);
            if (!uuid.equals(owner)) continue;
            if (found == null) {
                found = stand;
            } else {
                stand.remove();
            }
        }
        return found;
    }

    private void syncModelStand(CustomMobInstance instance) {
        ArmorStand stand = instance.getModelStand();
        if (stand == null || !stand.isValid()) return;

        LivingEntity entity = instance.getEntity();
        Location loc = entity.getLocation();
        loc.setY(loc.getY() + instance.getDefinition().getModel().getYOffset());

        // 動いていないときにテレポートパケットを撒かない
        Location current = stand.getLocation();
        if (current.getWorld() == loc.getWorld()
                && current.distanceSquared(loc) < SYNC_EPSILON
                && Math.abs(current.getYaw() - loc.getYaw()) < SYNC_EPSILON) {
            return;
        }
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

    public String getMobId(Entity entity) {
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

    /** モデル用ArmorStandかどうか(本体Mobと同じmobIdタグを持たせているので区別が要る) */
    public boolean isModelStand(Entity entity) {
        return entity.getPersistentDataContainer().has(standOwnerKey, PersistentDataType.STRING);
    }

    /** BukkitSchedulerから毎Tick呼ばれる想定 */
    public void tickAll() {
        tickCounter++;
        // ビヘイビア中の死亡(反撃ダメージなど)でマップが変更されうるのでスナップショットを回す
        List<CustomMobInstance> snapshot = new ArrayList<>(activeMobs.values());
        for (CustomMobInstance instance : snapshot) {
            LivingEntity entity = instance.getEntity();

            if (!entity.isValid() || entity.isDead()) {
                // チャンクアンロード時もここに来る。本体はワールドに残るので
                // EntitiesLoadEvent 側で拾い直される
                removeInstance(entity.getUniqueId());
                continue;
            }

            if (instance.getModelStand() != null) {
                syncModelStand(instance);
            }

            for (AiBehaviorConfig behaviorConfig : instance.getDefinition().getAiBehaviors()) {
                AiBehavior behavior = behaviorRegistry.get(behaviorConfig.getType());
                if (behavior == null) continue;
                try {
                    behavior.tick(instance, behaviorConfig, tickCounter);
                } catch (Exception ex) {
                    // 1体の例外で残りのMobのTickまで止めない
                    logBehaviorFailure(instance, behaviorConfig, ex);
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

    private void logBehaviorFailure(CustomMobInstance instance, AiBehaviorConfig config, Exception ex) {
        String key = instance.getDefinition().getId() + "/" + config.getType() + "/" + ex.getClass().getName();
        if (!loggedBehaviorFailures.add(key)) return; // 毎Tick同じ例外が出るのでログは初回だけ
        plugin.getLogger().log(Level.WARNING,
                "AI処理で例外が発生しました (" + instance.getDefinition().getId() + " / " + config.getType()
                        + ")。同じ例外の2回目以降はログを省略します", ex);
    }

    public CustomMobInstance getInstance(UUID entityId) {
        return activeMobs.get(entityId);
    }

    public int getActiveCount() {
        return activeMobs.size();
    }

    public void removeInstance(UUID entityId) {
        CustomMobInstance instance = activeMobs.remove(entityId);
        if (instance != null && instance.getModelStand() != null) {
            instance.getModelStand().remove();
        }
    }

    /**
     * 読み込み済みチャンクにいるカスタムMobとモデル用Standをまとめて消す。
     * mobIdFilter が null なら全部が対象。プレイヤー不要なのでコンソール/rconからも実行できる。
     *
     * @return 消した本体Mobの数(モデル用Standは数えない)
     */
    public int cleanup(String mobIdFilter) {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                String mobId = getMobId(entity);
                if (mobId == null) continue;
                if (mobIdFilter != null && !mobIdFilter.equalsIgnoreCase(mobId)) continue;

                boolean stand = isModelStand(entity);
                removeInstance(entity.getUniqueId());
                entity.remove();
                if (!stand) removed++;
            }
        }
        return removed;
    }

    /** プラグイン無効化時の後始末。本体Mobはワールドに残し、モデル用Standだけ片付ける */
    public void shutdown() {
        for (CustomMobInstance instance : activeMobs.values()) {
            ArmorStand stand = instance.getModelStand();
            if (stand != null && stand.isValid()) {
                stand.remove();
            }
        }
        activeMobs.clear();
    }
}
