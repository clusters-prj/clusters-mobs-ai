package com.kaguya.custommobs.gui;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.model.CustomMobInstance;
import com.kaguya.custommobs.model.ModelConfig;
import com.kaguya.custommobs.model.StatBlock;
import com.kaguya.custommobs.pet.BuildJob;
import com.kaguya.custommobs.pet.PetManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ペットを右クリックしたときに開くチェスト形式のGUI。
 * <p>
 * 背景テクスチャ(gui/container/generic_54.png)は差し替えず、枠をガラス板アイテムで覆うことで
 * 「ただのチェスト」感を薄める(差し替えると本物のチェストの見た目まで変わってしまうため)。
 * アイコンはMobモデル用に既に用意されているリソースパックのカスタムテクスチャ({@link ModelConfig})
 * をそのまま流用する。
 * <p>
 * レイアウト(54スロット、0〜53):
 * <ul>
 *   <li>1段目・6段目・各行の両端列: 枠(ガラス板)
 *   <li>2段目: アイコン(左)、HP・所有者・種別の情報タイル(右)
 *   <li>3段目: 建築進捗(あれば)
 *   <li>4〜5段目: 設計図ボタン(所有者のみ、ページング対応)
 *   <li>6段目: ページ送り + 「ついてきて」トグル(所有者のみ)
 * </ul>
 * ボタンの識別はスロット番号ではなく、アイテムのPersistentDataContainerに埋めたタグで行う。
 */
public class PetMenu implements Listener {

    private static final int SIZE = 54;
    private static final int SLOT_ICON = 10;
    private static final int SLOT_HP = 12;
    private static final int SLOT_OWNER = 14;
    private static final int SLOT_SPECIES = 16;
    private static final int SLOT_BUILD = 22;
    private static final int SLOT_PREV_PAGE = 46;
    private static final int SLOT_FOLLOW = 49;
    private static final int SLOT_NEXT_PAGE = 52;
    /** 3〜4段目の、枠を除いた設計図ボタン用スロット(1ページあたり最大14件) */
    private static final int[] BLUEPRINT_SLOTS = {
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
    };

    private static final String ACTION_FOLLOW_TOGGLE = "follow_toggle";
    private static final String ACTION_PAGE_PREV = "page_prev";
    private static final String ACTION_PAGE_NEXT = "page_next";
    private static final String ACTION_BUILD_PREFIX = "build:";

    private final PetManager petManager;
    private final NamespacedKey actionKey;

    public PetMenu(JavaPlugin plugin, PetManager petManager) {
        this.petManager = petManager;
        this.actionKey = new NamespacedKey(plugin, "pet_menu_action");
    }

    public void open(Player player, CustomMobInstance instance) {
        boolean manageable = petManager.canManage(player, instance);
        PetMenuHolder holder = new PetMenuHolder(instance, manageable);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, legacyTitle(instance));
        holder.setInventory(inventory);

        render(holder);
        player.openInventory(inventory);

        if (!manageable) return;

        petManager.listOwnedBlueprintsAsync(player.getUniqueId(), (blueprints, error) -> {
            // その間にメニューを閉じた/別のメニューを開いた場合は書き込まない
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inventory) return;
            holder.setBlueprints(blueprints);
            render(holder);
            player.updateInventory();
        });
    }

    private void render(PetMenuHolder holder) {
        Inventory inventory = holder.getInventory();
        CustomMobInstance instance = holder.getInstance();

        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, border());
        }

        inventory.setItem(SLOT_ICON, iconItem(instance));
        inventory.setItem(SLOT_HP, hpItem(instance));
        inventory.setItem(SLOT_OWNER, ownerItem(instance));
        inventory.setItem(SLOT_SPECIES, speciesItem(instance));

        BuildJob build = instance.getActiveBuild();
        if (build != null) {
            inventory.setItem(SLOT_BUILD, buildItem(build));
        }

        if (!holder.isManageable()) {
            inventory.setItem(31, infoOnlyItem());
            return;
        }

        renderBlueprintPage(holder);
        inventory.setItem(SLOT_FOLLOW, followToggleItem(instance));
    }

    private void renderBlueprintPage(PetMenuHolder holder) {
        Inventory inventory = holder.getInventory();
        List<PetDatabase.BlueprintListing> blueprints = holder.getBlueprints();

        if (blueprints == null) {
            inventory.setItem(BLUEPRINT_SLOTS[BLUEPRINT_SLOTS.length / 2], loadingItem());
            return;
        }
        if (blueprints.isEmpty()) {
            inventory.setItem(BLUEPRINT_SLOTS[BLUEPRINT_SLOTS.length / 2], noBlueprintsItem());
            return;
        }

        int perPage = BLUEPRINT_SLOTS.length;
        int maxPage = Math.max(0, (blueprints.size() - 1) / perPage);
        int page = Math.min(Math.max(0, holder.getPage()), maxPage);
        holder.setPage(page);

        int start = page * perPage;
        int end = Math.min(blueprints.size(), start + perPage);
        for (int i = start; i < end; i++) {
            inventory.setItem(BLUEPRINT_SLOTS[i - start], blueprintItem(blueprints.get(i)));
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREV_PAGE, pageButton("前のページ", ACTION_PAGE_PREV));
        }
        if (page < maxPage) {
            inventory.setItem(SLOT_NEXT_PAGE, pageButton("次のページ", ACTION_PAGE_NEXT));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PetMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        String action = clicked.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;

        CustomMobInstance instance = holder.getInstance();
        if (!instance.getEntity().isValid()) {
            player.closeInventory();
            return;
        }

        if (action.equals(ACTION_FOLLOW_TOGGLE)) {
            petManager.setFollowing(player, instance, !instance.isFollowingOwner());
            render(holder);
            player.updateInventory();
        } else if (action.equals(ACTION_PAGE_PREV)) {
            holder.setPage(holder.getPage() - 1);
            render(holder);
            player.updateInventory();
        } else if (action.equals(ACTION_PAGE_NEXT)) {
            holder.setPage(holder.getPage() + 1);
            render(holder);
            player.updateInventory();
        } else if (action.startsWith(ACTION_BUILD_PREFIX)) {
            int listingId = Integer.parseInt(action.substring(ACTION_BUILD_PREFIX.length()));
            petManager.assignBuild(player, instance, listingId);
            player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PetMenuHolder) {
            event.setCancelled(true);
        }
    }

    private String legacyTitle(CustomMobInstance instance) {
        // インベントリタイトルはレガシーカラーコードをそのまま解釈してくれるのでStringのままでよい
        return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                instance.getDefinition().getDisplayName().replace('§', '&'));
    }

    private ItemStack border() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack iconItem(CustomMobInstance instance) {
        ModelConfig model = instance.getDefinition().getModel();
        ItemStack item;
        if (model != null) {
            item = new ItemStack(model.getMaterial());
            ItemMeta meta = item.getItemMeta();
            meta.setCustomModelData(model.getCustomModelData());
            meta.setDisplayName("§e" + instance.getDefinition().getDisplayName());
            item.setItemMeta(meta);
        } else {
            item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§e" + instance.getDefinition().getDisplayName());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack hpItem(CustomMobInstance instance) {
        StatBlock stats = instance.getDefinition().getStats();
        return plainItem(Material.GOLDEN_APPLE, "§cHP",
                String.format(Locale.ROOT, "§f%.1f / %.1f", instance.getEntity().getHealth(), stats.getHealth()));
    }

    private ItemStack ownerItem(CustomMobInstance instance) {
        var ownerUuid = instance.getOwnerUuid();
        String line;
        if (ownerUuid == null) {
            line = "§8未所有";
        } else {
            String ownerName = Bukkit.getOfflinePlayer(ownerUuid).getName();
            line = "§f" + (ownerName != null ? ownerName : ownerUuid);
        }
        return plainItem(Material.PLAYER_HEAD, "§7所有者", line);
    }

    private ItemStack speciesItem(CustomMobInstance instance) {
        return plainItem(Material.NAME_TAG, "§7種別", "§f" + instance.getDefinition().getId());
    }

    private ItemStack buildItem(BuildJob job) {
        String progress = job.getNextIndex() + " / " + job.getBlueprint().getBlocks().size();
        return plainItem(Material.BRICKS, "§7建築中",
                "§f" + progress, "§7設計図「" + job.getBlueprint().getName() + "」");
    }

    private ItemStack infoOnlyItem() {
        return plainItem(Material.BOOK, "§7他プレイヤーのペットです", "§8操作はできません");
    }

    private ItemStack loadingItem() {
        return plainItem(Material.CLOCK, "§7設計図を読み込み中...");
    }

    private ItemStack noBlueprintsItem() {
        return plainItem(Material.BARRIER, "§7所有している設計図がありません");
    }

    private ItemStack blueprintItem(PetDatabase.BlueprintListing listing) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + listing.title());
        meta.setLore(List.of("§7クリックで建築を開始"));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING,
                ACTION_BUILD_PREFIX + listing.id());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack followToggleItem(CustomMobInstance instance) {
        boolean following = instance.isFollowingOwner();
        ItemStack item = new ItemStack(Material.LEAD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(following ? "§aついてきて: ON" : "§7ついてきて: OFF");
        meta.setLore(List.of("§7クリックで切り替え"));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, ACTION_FOLLOW_TOGGLE);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageButton(String label, String action) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + label);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack plainItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(new ArrayList<>(List.of(lore)));
        }
        item.setItemMeta(meta);
        return item;
    }
}
