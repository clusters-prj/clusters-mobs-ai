package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.model.AiBehaviorConfig;
import com.kaguya.custommobs.model.DropEntry;
import com.kaguya.custommobs.model.MobDefinition;
import com.kaguya.custommobs.model.ModelConfig;
import com.kaguya.custommobs.model.StatBlock;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class MobDefinitionLoader {

    /** attributeの上限(GENERIC_MAX_HEALTH等)。超えるとsetBaseValueが例外を投げる */
    private static final double MAX_ATTRIBUTE_VALUE = 1024.0;

    private final Logger logger;

    public MobDefinitionLoader(Logger logger) {
        this.logger = logger;
    }

    public Map<String, MobDefinition> load(File mobsYamlFile) {
        Map<String, MobDefinition> result = new LinkedHashMap<>();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(mobsYamlFile);

        if (!yaml.isConfigurationSection("mobs")) {
            logger.warning("mobs.yml に 'mobs' セクションが見つかりません");
            return result;
        }

        for (String id : yaml.getConfigurationSection("mobs").getKeys(false)) {
            String base = "mobs." + id + ".";
            try {
                EntityType baseEntity = parseBaseEntity(id, yaml.getString(base + "base-entity"));
                if (baseEntity == null) continue; // 原因はparseBaseEntity側でログ済み

                String displayName = yaml.getString(base + "display-name", id);

                StatBlock stats = new StatBlock(
                        clamp(yaml.getDouble(base + "stats.health", 20.0), 1.0, MAX_ATTRIBUTE_VALUE),
                        Math.max(0.0, yaml.getDouble(base + "stats.damage", 3.0)),
                        clamp(yaml.getDouble(base + "stats.armor", 0.0), 0.0, 30.0),
                        clamp(yaml.getDouble(base + "stats.movement-speed", 0.25), 0.0, MAX_ATTRIBUTE_VALUE)
                );

                List<DropEntry> drops = loadDrops(id, yaml, base);
                List<AiBehaviorConfig> aiList = loadAiBehaviors(id, yaml, base);
                ModelConfig model = loadModel(id, yaml, base);

                result.put(id, new MobDefinition(id, baseEntity, displayName, stats, drops, aiList, model));
                logger.info("Mob定義ロード完了: " + id);
            } catch (Exception ex) {
                logger.warning("Mob定義の読み込みに失敗しました (" + id + "): "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }

        return result;
    }

    /**
     * base-entity を検証する。ここで弾いておかないと spawn 時に
     * ClassCastException / IllegalArgumentException になって初めて気づくことになる。
     */
    private EntityType parseBaseEntity(String id, String raw) {
        if (raw == null || raw.isBlank()) {
            logger.warning("base-entity が未指定です (" + id + ")");
            return null;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("base-entity が不正です (" + id + "): " + raw);
            return null;
        }
        Class<?> entityClass = type.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            logger.warning("base-entity がLivingEntityではありません (" + id + "): " + raw);
            return null;
        }
        if (!type.isSpawnable()) {
            logger.warning("base-entity はスポーンできない種類です (" + id + "): " + raw);
            return null;
        }
        return type;
    }

    private List<DropEntry> loadDrops(String id, YamlConfiguration yaml, String base) {
        List<DropEntry> drops = new ArrayList<>();
        if (!yaml.isList(base + "drops")) return drops;

        for (Map<?, ?> raw : yaml.getMapList(base + "drops")) {
            Object itemName = raw.get("item");
            Material mat = itemName == null ? null : Material.matchMaterial(itemName.toString());
            if (mat == null || !mat.isItem()) {
                logger.warning("drops の item が不正なので無視します (" + id + "): " + itemName);
                continue;
            }
            double chance = clamp(toDouble(raw.get("chance"), 1.0), 0.0, 1.0);
            int min = Math.max(0, toInt(raw.get("amount-min"), 1));
            int max = Math.max(min, toInt(raw.get("amount-max"), min));
            drops.add(new DropEntry(mat, chance, min, max));
        }
        return drops;
    }

    private List<AiBehaviorConfig> loadAiBehaviors(String id, YamlConfiguration yaml, String base) {
        List<AiBehaviorConfig> aiList = new ArrayList<>();
        if (!yaml.isList(base + "ai")) return aiList;

        for (Map<?, ?> raw : yaml.getMapList(base + "ai")) {
            Object type = raw.get("type");
            if (type == null) {
                logger.warning("ai の type が未指定なので無視します (" + id + ")");
                continue;
            }
            Map<String, Object> params = new HashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null && !"type".equals(e.getKey())) {
                    params.put(e.getKey().toString(), e.getValue());
                }
            }
            aiList.add(new AiBehaviorConfig(type.toString(), params));
        }
        return aiList;
    }

    private ModelConfig loadModel(String id, YamlConfiguration yaml, String base) {
        if (!yaml.isConfigurationSection(base + "model")) return null;

        String rawItem = yaml.getString(base + "model.item", "PLAYER_HEAD");
        Material modelMat = rawItem == null ? null : Material.matchMaterial(rawItem);
        if (modelMat == null || !modelMat.isItem()) {
            logger.warning("model.item が不正なのでカスタムモデルを無効にします (" + id + "): " + rawItem);
            return null;
        }
        int customModelData = Math.max(0, yaml.getInt(base + "model.custom-model-data", 0));
        float scale = (float) yaml.getDouble(base + "model.scale", 1.0);
        double yOffset = yaml.getDouble(base + "model.y-offset", 0.0);
        return new ModelConfig(modelMat, customModelData, scale, yOffset);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static double toDouble(Object value, double def) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static int toInt(Object value, int def) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
