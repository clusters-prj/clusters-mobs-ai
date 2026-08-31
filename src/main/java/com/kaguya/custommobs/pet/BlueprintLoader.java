package com.kaguya.custommobs.pet;

import com.google.gson.Gson;
import org.bukkit.Material;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public class BlueprintLoader {

    private final File blueprintDir;
    private final Logger logger;
    private final Gson gson = new Gson();

    public BlueprintLoader(File blueprintDir, Logger logger) {
        this.blueprintDir = blueprintDir;
        this.logger = logger;
    }

    /** @return 読み込んだ設計図。ファイルが無い/壊れている場合は null */
    public Blueprint load(String name) {
        File file = new File(blueprintDir, name + ".json");
        if (!file.exists()) {
            logger.warning("設計図が見つかりません: " + file.getPath());
            return null;
        }
        try (FileReader reader = new FileReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            Blueprint blueprint = gson.fromJson(reader, Blueprint.class);
            if (blueprint == null || blueprint.getBlocks() == null) {
                logger.warning("設計図の中身が空です: " + file.getPath());
                return null;
            }
            return validate(blueprint, file.getPath());
        } catch (IOException | com.google.gson.JsonSyntaxException ex) {
            logger.warning("設計図の読み込みに失敗しました (" + file.getPath() + "): " + ex.getMessage());
            return null;
        }
    }

    /** material名が不正なブロックは警告を出してスキップする(1個の誤字で設計図全体を無効にしない) */
    private Blueprint validate(Blueprint blueprint, String path) {
        List<Blueprint.BlockEntry> valid = new ArrayList<>();
        for (Blueprint.BlockEntry entry : blueprint.getBlocks()) {
            Material material = parseMaterial(entry.getMaterial());
            if (material == null) {
                logger.warning("設計図に不正なmaterialがあるためスキップします (" + path + "): " + entry.getMaterial());
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
