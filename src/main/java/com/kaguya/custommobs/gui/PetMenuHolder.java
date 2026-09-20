package com.kaguya.custommobs.gui;

import com.kaguya.custommobs.database.PetDatabase;
import com.kaguya.custommobs.model.CustomMobInstance;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * ペットメニューGUI(チェスト形式)のInventoryと状態を紐づけるためのホルダー。
 * {@link PetMenu}が生成・更新し、{@code InventoryClickEvent}側で
 * {@code instanceof PetMenuHolder}を見て自分のGUIかどうかを判定する。
 */
public class PetMenuHolder implements InventoryHolder {

    private final CustomMobInstance instance;
    private final boolean manageable;
    private Inventory inventory;
    /** null = まだDBから読み込み中 */
    private List<PetDatabase.BlueprintListing> blueprints;
    private int page = 0;

    public PetMenuHolder(CustomMobInstance instance, boolean manageable) {
        this.instance = instance;
        this.manageable = manageable;
    }

    public CustomMobInstance getInstance() { return instance; }
    public boolean isManageable() { return manageable; }

    public void setInventory(Inventory inventory) { this.inventory = inventory; }

    public List<PetDatabase.BlueprintListing> getBlueprints() { return blueprints; }
    public void setBlueprints(List<PetDatabase.BlueprintListing> blueprints) { this.blueprints = blueprints; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    @Override
    public Inventory getInventory() { return inventory; }
}
