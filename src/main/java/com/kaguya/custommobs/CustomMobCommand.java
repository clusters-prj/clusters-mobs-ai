package com.kaguya.custommobs;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.manager.MobManager;
import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.pet.PetManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;

public class CustomMobCommand implements CommandExecutor {

    /** 視線でペットを狙う際の最大距離(ブロック) */
    private static final double TARGET_RANGE = 8.0;

    private final MobManager mobManager;
    private final PetManager petManager;

    public CustomMobCommand(MobManager mobManager, PetManager petManager) {
        this.mobManager = mobManager;
        this.petManager = petManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cプレイヤーのみ実行できます");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§c使い方: /cmob spawn <mobId>");
                    return true;
                }
                var instance = mobManager.spawn(args[1], player.getLocation());
                if (instance == null) {
                    sender.sendMessage("§c該当するMob定義がありません: " + args[1]);
                } else {
                    sender.sendMessage("§aスポーンしました: " + args[1]);
                }
            }
            case "reload" -> {
                mobManager.reloadDefinitions();
                sender.sendMessage("§aMob定義をリロードしました (" + mobManager.getAllDefinitions().size() + "件)");
            }
            case "list" -> sender.sendMessage("§e登録済みMob: " + String.join(", ", mobManager.getAllDefinitions().keySet()));
            case "tame" -> handleTame(sender);
            case "release" -> handleRelease(sender);
            case "build" -> handleBuild(sender, args);
            case "mypets" -> handleMyPets(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§c使い方:");
        sender.sendMessage("§7 /cmob spawn <mobId>");
        sender.sendMessage("§7 /cmob reload");
        sender.sendMessage("§7 /cmob list");
        sender.sendMessage("§7 /cmob tame  §8- 視線の先のMobをテイム");
        sender.sendMessage("§7 /cmob release  §8- 視線の先の自分のペットを手放す");
        sender.sendMessage("§7 /cmob build <blueprintName>  §8- 視線の先の自分のペットに建築させる");
        sender.sendMessage("§7 /cmob mypets  §8- 自分のペット一覧");
    }

    private void handleTame(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        CustomMobInstance target = findTargetedInstance(player);
        if (target == null) {
            player.sendMessage("§c視線の先にカスタムMobがいません");
            return;
        }
        petManager.tame(player, target);
    }

    private void handleRelease(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        CustomMobInstance target = findTargetedInstance(player);
        if (target == null) {
            player.sendMessage("§c視線の先にカスタムMobがいません");
            return;
        }
        petManager.release(player, target);
    }

    private void handleBuild(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (args.length < 2) {
            player.sendMessage("§c使い方: /cmob build <blueprintName>");
            return;
        }
        CustomMobInstance target = findTargetedInstance(player);
        if (target == null) {
            player.sendMessage("§c視線の先にカスタムMobがいません");
            return;
        }
        petManager.assignBuild(player, target, args[1]);
    }

    private void handleMyPets(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!petManager.isDatabaseReady()) {
            player.sendMessage("§cペット用データベースに接続できていません");
            return;
        }
        try {
            List<PetDatabase.PetRecord> pets = petManager.listOwned(player.getUniqueId());
            if (pets.isEmpty()) {
                player.sendMessage("§eペットを飼っていません");
                return;
            }
            player.sendMessage("§e所有ペット (" + pets.size() + "):");
            for (PetDatabase.PetRecord pet : pets) {
                player.sendMessage("§7 - " + pet.mobType() + " §8(" + pet.serverId() + ")");
            }
        } catch (SQLException e) {
            player.sendMessage("§cペット一覧の取得に失敗しました");
        }
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage("§cプレイヤーのみ実行できます");
        return null;
    }

    /** プレイヤーの視線上にある最も近いカスタムMobを探す */
    private CustomMobInstance findTargetedInstance(Player player) {
        Entity looked = player.getTargetEntity((int) TARGET_RANGE);
        if (!(looked instanceof LivingEntity entity)) return null;
        return mobManager.getInstance(entity.getUniqueId());
    }
}
