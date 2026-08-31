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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CustomMobCommand implements CommandExecutor, TabCompleter {

    /** spawn/reload/list/cleanup など、サーバー管理者向けのサブコマンドに要求する権限 */
    private static final String ADMIN_PERMISSION = "custommobs.command";
    /**
     * tame/release/build/mypets/claim など、プレイヤーが自分のペットを扱うためのサブコマンドに
     * 要求する権限。これを{@code ADMIN_PERMISSION}と分けているのは、通常プレイヤーにペット機能
     * だけ渡せるようにするため(admin権限まで渡すと他人のペットの所有者チェックまで
     * 素通りしてしまう。{@link com.kaguya.custommobs.pet.PetManager}参照)。
     */
    private static final String PET_PERMISSION = "custommobs.pet";
    private static final Set<String> ADMIN_SUB_COMMANDS = Set.of("spawn", "reload", "list", "cleanup");
    private static final List<String> SUB_COMMANDS =
            List.of("spawn", "reload", "list", "cleanup", "tame", "release", "build", "mypets", "claim");

    /** 視線でペットを狙う際の最大距離(ブロック) */
    private static final double TARGET_RANGE = 8.0;
    /** 視線からこの角度(度)以内に入っていれば「狙っている」とみなす */
    private static final double TARGET_ANGLE_DEGREES = 25.0;

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

        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean adminSub = ADMIN_SUB_COMMANDS.contains(sub);
        if (adminSub ? !sender.hasPermission(ADMIN_PERMISSION) : !hasPetAccess(sender)) {
            sender.sendMessage("§cこのコマンドを実行する権限がありません");
            return true;
        }

        switch (sub) {
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

    /** プレイヤー向けペット機能(tame/release/build/mypets/claim)を使える権限があるか */
    private boolean hasPetAccess(CommandSender sender) {
        return sender.hasPermission(PET_PERMISSION) || sender.hasPermission(ADMIN_PERMISSION);
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage("§cプレイヤーのみ実行できます");
        return null;
    }

    /**
     * プレイヤーの視線方向に最も近いカスタムMobを探す。
     * <p>
     * {@code Player#getTargetEntity}のような正確なレイキャストは使わない。見た目の
     * 巨大なモデルはリソースパック側でアイテムを引き伸ばして描画しているだけで、実際の
     * 当たり判定(本体・モデル用ArmorStandとも)は元のサイズの小さい箱のままのため、
     * 見た目の中心を狙っても物理的に光線が当たらず、レイキャスト方式では実用にならない。
     * 代わりに、視線の向きから一定角度以内にいる最も近いカスタムMobを「狙っている」とみなす。
     */
    private CustomMobInstance findTargetedInstance(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        CustomMobInstance nearest = null;
        double nearestDist = TARGET_RANGE;
        for (CustomMobInstance instance : mobManager.getActiveInstances()) {
            LivingEntity entity = instance.getEntity();
            if (!entity.getWorld().equals(eye.getWorld())) continue;

            Vector toMob = entity.getLocation().toVector()
                    .add(new Vector(0, entity.getHeight() / 2.0, 0))
                    .subtract(eye.toVector());
            double dist = toMob.length();
            if (dist < 1.0E-4 || dist > TARGET_RANGE) continue;
            if (Math.toDegrees(direction.angle(toMob)) > TARGET_ANGLE_DEGREES) continue;

            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = instance;
            }
        }
        return nearest;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);
        boolean pet = admin || hasPetAccess(sender);
        if (!admin && !pet) return Collections.emptyList();

        if (args.length == 1) {
            List<String> visible = new ArrayList<>();
            for (String candidate : SUB_COMMANDS) {
                if (ADMIN_SUB_COMMANDS.contains(candidate) ? admin : pet) {
                    visible.add(candidate);
                }
            }
            return filterPrefix(visible, args[0]);
        }
        if (args.length == 2 && admin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("spawn") || sub.equals("cleanup")) {
                return filterPrefix(new ArrayList<>(mobManager.getAllDefinitions().keySet()), args[1]);
            }
        }
        if (args.length == 6 && admin && args[0].equalsIgnoreCase("spawn")) {
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
