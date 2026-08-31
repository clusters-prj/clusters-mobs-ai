package com.kaguya.custommobs;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.manager.MobManager;
import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.pet.PetManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CustomMobCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "custommobs.command";
    private static final List<String> SUB_COMMANDS =
            List.of("spawn", "reload", "list", "cleanup", "tame", "release", "build", "mypets", "claim");

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
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> handleSpawn(sender, args);
            case "reload" -> {
                mobManager.reloadDefinitions();
                petManager.syncCatalog();
                sender.sendMessage("§aMob定義をリロードしました (" + mobManager.getAllDefinitions().size() + "件)");
            }
            case "list" -> {
                if (mobManager.getAllDefinitions().isEmpty()) {
                    sender.sendMessage("§eMob定義がありません (mobs.yml を確認してください)");
                } else {
                    sender.sendMessage("§e登録済みMob: " + String.join(", ", mobManager.getAllDefinitions().keySet()));
                    sender.sendMessage("§7稼働中のカスタムMob: " + mobManager.getActiveCount() + "体");
                }
            }
            case "cleanup" -> handleCleanup(sender, args);
            case "tame" -> handleTame(sender);
            case "release" -> handleRelease(sender);
            case "build" -> handleBuild(sender, args);
            case "mypets" -> handleMyPets(sender);
            case "claim" -> handleClaim(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§c使い方:");
        sender.sendMessage("§7 /cmob spawn <mobId> [x y z] [world]");
        sender.sendMessage("§7 /cmob reload");
        sender.sendMessage("§7 /cmob list");
        sender.sendMessage("§7 /cmob cleanup [mobId]  §8- 読み込み済みチャンクのカスタムMobを掃除");
        sender.sendMessage("§7 /cmob tame  §8- 視線の先のMobをテイム");
        sender.sendMessage("§7 /cmob release  §8- 視線の先の自分のペットを手放す");
        sender.sendMessage("§7 /cmob build <listingId>  §8- マーケットプレイスで購入済みの設計図で視線の先の自分のペットに建築させる");
        sender.sendMessage("§7 /cmob mypets  §8- 自分のペット一覧");
        sender.sendMessage("§7 /cmob claim  §8- Webショップで購入したペットを受け取る");
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c使い方: /cmob spawn <mobId> [x y z] [world]");
            return;
        }
        String mobId = args[1];
        if (mobManager.getDefinition(mobId) == null) {
            sender.sendMessage("§c該当するMob定義がありません: " + mobId);
            return;
        }

        Location location = resolveLocation(sender, args);
        if (location == null) return; // 原因はresolveLocation側で通知済み

        if (mobManager.spawn(mobId, location) == null) {
            sender.sendMessage("§cスポーンに失敗しました: " + mobId + " (サーバーログを確認してください)");
        } else {
            sender.sendMessage("§aスポーンしました: " + mobId
                    + " §7(" + location.getWorld().getName()
                    + " " + fmt(location.getX()) + " " + fmt(location.getY()) + " " + fmt(location.getZ()) + ")");
        }
    }

    /** 座標指定があればそれを使う。無ければプレイヤーの現在地(コンソールからは座標必須) */
    private Location resolveLocation(CommandSender sender, String[] args) {
        if (args.length >= 5) {
            World world;
            if (args.length >= 6) {
                world = Bukkit.getWorld(args[5]);
                if (world == null) {
                    sender.sendMessage("§cそのワールドは見つかりません: " + args[5]);
                    return null;
                }
            } else if (sender instanceof Player player) {
                world = player.getWorld();
            } else {
                world = Bukkit.getWorlds().get(0);
            }
            try {
                return new Location(world,
                        Double.parseDouble(args[2]),
                        Double.parseDouble(args[3]),
                        Double.parseDouble(args[4]));
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c座標は数値で指定してください: " + args[2] + " " + args[3] + " " + args[4]);
                return null;
            }
        }
        if (sender instanceof Player player) {
            return player.getLocation();
        }
        sender.sendMessage("§cコンソールから実行する場合は座標を指定してください: /cmob spawn <mobId> <x> <y> <z> [world]");
        return null;
    }

    private void handleCleanup(CommandSender sender, String[] args) {
        String filter = args.length >= 2 ? args[1] : null;
        int removed = mobManager.cleanup(filter);
        sender.sendMessage("§a読み込み済みチャンクのカスタムMobを削除しました: " + removed + "体"
                + (filter == null ? "" : " §7(" + filter + ")"));
        sender.sendMessage("§7※ 読み込まれていないチャンクのMobは対象外です");
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
            player.sendMessage("§c使い方: /cmob build <listingId>  §7(マーケットプレイスで購入した設計図の出品ID)");
            return;
        }
        int listingId;
        try {
            listingId = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            player.sendMessage("§clistingIdは数値で指定してください: " + args[1]);
            return;
        }
        CustomMobInstance target = findTargetedInstance(player);
        if (target == null) {
            player.sendMessage("§c視線の先にカスタムMobがいません");
            return;
        }
        petManager.assignBuild(player, target, listingId);
    }

    private void handleMyPets(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!petManager.isDatabaseReady()) {
            player.sendMessage("§cペット用データベースに接続できていません");
            return;
        }
        petManager.listOwnedAsync(player.getUniqueId(), (pets, error) -> {
            if (error != null) {
                player.sendMessage("§cペット一覧の取得に失敗しました");
                return;
            }
            if (pets.isEmpty()) {
                player.sendMessage("§eペットを飼っていません");
                return;
            }
            player.sendMessage("§e所有ペット (" + pets.size() + "):");
            for (PetDatabase.PetRecord pet : pets) {
                player.sendMessage("§7 - " + pet.mobType() + " §8(" + pet.serverId() + ")");
            }
        });
    }

    private void handleClaim(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        petManager.claim(player);
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

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return Collections.emptyList();

        if (args.length == 1) {
            return filterPrefix(SUB_COMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("spawn") || sub.equals("cleanup")) {
                return filterPrefix(new ArrayList<>(mobManager.getAllDefinitions().keySet()), args[1]);
            }
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("spawn")) {
            List<String> worlds = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                worlds.add(world.getName());
            }
            return filterPrefix(worlds, args[5]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
