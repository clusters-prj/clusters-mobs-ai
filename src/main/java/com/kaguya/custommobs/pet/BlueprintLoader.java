package com.kaguya.custommobs.pet;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * マーケットプレイス(marketplace_listings.blueprint_json)から取得したJSON文字列を
 * Blueprintオブジェクトへ変換する。設計図の実体はWeb側のDBが持つため、
 * このクラスはファイルI/Oを一切行わない(パースとバリデーションのみ)。
 */
public class BlueprintLoader {

    private final Logger logger;
    private final Gson gson = new Gson();

    public BlueprintLoader(Logger logger) {
        this.logger = logger;
    }

    /**
     * @param json         marketplace_listings.blueprint_json の中身
     * @param sourceLabel  ログ表示用のラベル(出品タイトルなど)
     * @return パース済みの設計図。壊れている場合は null
     */
    public Blueprint parse(String json, String sourceLabel) {
        if (json == null || json.isBlank()) {
            logger.warning("設計図の中身が空です: " + sourceLabel);
            return null;
        }
        try {
            Blueprint blueprint = gson.fromJson(json, Blueprint.class);
            if (blueprint == null || blueprint.getBlocks() == null) {
                logger.warning("設計図の中身が空です: " + sourceLabel);
                return null;
            }
            return validate(blueprint, sourceLabel);
        } catch (JsonSyntaxException ex) {
            logger.warning("設計図の読み込みに失敗しました (" + sourceLabel + "): " + ex.getMessage());
            return null;
        }
    }

    /** material名が不正なブロックは警告を出してスキップする(1個の誤字で設計図全体を無効にしない) */
    private Blueprint validate(Blueprint blueprint, String sourceLabel) {
        List<Blueprint.BlockEntry> valid = new ArrayList<>();
        for (Blueprint.BlockEntry entry : blueprint.getBlocks()) {
            Material material = parseMaterial(entry.getMaterial());
            if (material == null) {
                logger.warning("設計図に不正なmaterialがあるためスキップします (" + sourceLabel + "): " + entry.getMaterial());
                continue;
            }
            valid.add(entry);
        }
        blueprint.getBlocks().retainAll(valid);
        return blueprint;
    }

    private Material parseMaterial(String name) {
        if (name == null) return null;
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
