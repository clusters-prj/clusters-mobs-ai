package com.kaguya.custommobs.integration;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ペットが建築で置いたブロックをCoreProtectのログに記録する。
 * <p>
 * {@code Block#setType} を直接呼ぶだけでは {@code BlockPlaceEvent} が発生しないため、
 * CoreProtectは何も記録しない(=/co lookup で追跡できず、既存ブロックを問答無用で
 * 上書きしても復元しようがない)。CoreProtectが公式に提供している
 * {@link CoreProtectAPI#logPlacement} を使って、所有者の行動として明示的に記録する。
 * <p>
 * CoreProtectのフォーク(customProtect等)はplugin.ymlのnameが異なるため名前では引けない。
 * {@code net.coreprotect.CoreProtect} を継承していればinstanceofで拾えるので、
 * FJEconomy({@code build/BuildRewardManager.java})と同じ探し方をする。
 */
public class CoreProtectLogger {

    /** これより古いAPIバージョンではlogPlacementの引数が異なるため対象外とする */
    private static final int MIN_API_VERSION = 9;

    private final JavaPlugin plugin;
    private CoreProtectAPI api;
    private boolean warned;

    public CoreProtectLogger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private CoreProtectAPI api() {
        if (api != null) return api;

        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (!(candidate instanceof CoreProtect coreProtectPlugin) || !candidate.isEnabled()) continue;

            CoreProtectAPI found = coreProtectPlugin.getAPI();
            if (found == null || !found.isEnabled()) continue;
            if (found.APIVersion() < MIN_API_VERSION) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().warning("CoreProtect(" + candidate.getName()
                            + ") のAPIバージョンが古いため、ペットの建築ログは記録されません: " + found.APIVersion());
                }
                continue;
            }
            api = found;
            plugin.getLogger().info("ペットの建築ログの記録先として " + candidate.getName()
                    + " を使用します (CoreProtect API v" + found.APIVersion() + ")");
            return api;
        }
        return null;
    }

    /** ブロック設置を所有者の行動としてCoreProtectに記録する。CoreProtect未導入なら何もしない */
    public void logPlacement(String ownerName, Location location, Material material, BlockData data) {
        CoreProtectAPI current = api();
        if (current == null) return;
        current.logPlacement(ownerName, location, material, data);
    }
}
