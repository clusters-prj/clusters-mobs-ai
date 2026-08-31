package com.kaguya.custommobs.manager;

import com.kaguya.custommobs.model.AiBehaviorConfig;
import com.kaguya.custommobs.model.DropEntry;
import com.kaguya.custommobs.model.MobDefinition;
import com.kaguya.custommobs.model.ModelConfig;
import com.kaguya.custommobs.model.PetConfig;
import com.kaguya.custommobs.model.StatBlock;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class MobDefinitionLoader {

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
                EntityType baseEntity = EntityType.valueOf(yaml.getString(base + "base-entity"));
                String displayName = yaml.getString(base + "display-name", id);

                StatBlock stats = new StatBlock(
                        yaml.getDouble(base + "stats.health", 20.0),
                        yaml.getDouble(base + "stats.damage", 3.0),
                        yaml.getDouble(base + "stats.armor", 0.0),
                        yaml.getDouble(base + "stats.movement-speed", 0.25)
                );

                List<DropEntry> drops = new ArrayList<>();
                if (yaml.isList(base + "drops")) {
                    for (Map<?, ?> rawUnbounded : yaml.getMapList(base + "drops")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> raw = (Map<String, Object>) rawUnbounded;
                        Material mat = Material.valueOf((String) raw.get("item"));
                        double chance = ((Number) raw.getOrDefault("chance", 1.0)).doubleValue();
                        int min = ((Number) raw.getOrDefault("amount-min", 1)).intValue();
                        int max = ((Number) raw.getOrDefault("amount-max", 1)).intValue();
                        drops.add(new DropEntry(mat, chance, min, max));
                    }
                }

                List<AiBehaviorConfig> aiList = new ArrayList<>();
                if (yaml.isList(base + "ai")) {
                    for (Map<?, ?> raw : yaml.getMapList(base + "ai")) {
                        String type = (String) raw.get("type");
                        Map<String, Object> params = new HashMap<>();
                        for (Map.Entry<?, ?> e : raw.entrySet()) {
                            if (!"type".equals(e.getKey())) {
                                params.put((String) e.getKey(), e.getValue());
                            }
                        }
                        aiList.add(new AiBehaviorConfig(type, params));
                    }
                }

                ModelConfig model = null;
                if (yaml.isConfigurationSection(base + "model")) {
                    Material modelMat = Material.valueOf(yaml.getString(base + "model.item", "PLAYER_HEAD"));
                    int customModelData = yaml.getInt(base + "model.custom-model-data", 0);
                    float scale = (float) yaml.getDouble(base + "model.scale", 1.0);
                    double yOffset = yaml.getDouble(base + "model.y-offset", 0.0);
                    model = new ModelConfig(modelMat, customModelData, scale, yOffset);
                }

                PetConfig pet = null;
                if (yaml.isConfigurationSection(base + "pet")) {
                    long tameCost = yaml.getLong(base + "pet.tame-cost", 0);
                    pet = new PetConfig(tameCost);
                }

                result.put(id, new MobDefinition(id, baseEntity, displayName, stats, drops, aiList, model, pet));
                logger.info("Mob定義ロード完了: " + id);
            } catch (Exception ex) {
                logger.warning("Mob定義の読み込みに失敗しました (" + id + "): " + ex.getMessage());
            }
        }

        return result;
    }
}
