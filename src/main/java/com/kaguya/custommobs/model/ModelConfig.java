package com.kaguya.custommobs.model;

import org.bukkit.Material;

public class ModelConfig {
    private final Material material;
    private final int customModelData;
    private final float scale;
    private final double yOffset;
    /**
     * 右クリック判定の視線コーン検索(MobManager#findNearestInView)専用の補正値。
     * <p>
     * モデル用ArmorStandの右腕は-90度回転させて水平に構えさせているため、モデル自体の
     * ジオメトリの「縦方向」の広がりはこの回転で「前方(エンティティの正面方向)」に変換される。
     * つまり見た目上の中心は本体の座標より前方にズレて見えるが、y-offsetは縦方向の補正しか
     * していないため、このズレを別途ここで持たせる。正面から見ると視線とほぼ一直線になり
     * 誤差が目立たないが、斜めから見ると誤差が大きく出る(要望を受けて追加)。
     */
    private final double forwardOffset;

    public ModelConfig(Material material, int customModelData, float scale, double yOffset, double forwardOffset) {
        this.material = material;
        this.customModelData = customModelData;
        this.scale = scale;
        this.yOffset = yOffset;
        this.forwardOffset = forwardOffset;
    }

    public Material getMaterial() { return material; }
    public int getCustomModelData() { return customModelData; }
    public float getScale() { return scale; }
    public double getYOffset() { return yOffset; }
    public double getForwardOffset() { return forwardOffset; }
}
