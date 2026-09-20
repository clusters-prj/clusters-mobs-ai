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
    /**
     * こちらも右クリック判定の視線コーン検索専用の補正値。
     * <p>
     * {@code y-offset}はモデル用ArmorStandの実際の設置高さ(見た目そのもの)を決めるため、
     * 狙い判定の都合だけでこの値をいじると見た目の位置までズレてしまう。狙い判定側だけを
     * 追加調整したい場合はこちらを使う(実機での見え方に合わせて{@code aim-forward-offset}と
     * 同様に微調整する想定)
     */
    private final double aimVerticalOffset;
    /**
     * こちらも右クリック判定の視線コーン検索専用の補正値。
     * <p>
     * forward/verticalだけでは吸収できない左右方向のズレがあったため追加。正の値は
     * エンティティの正面から見て「左」方向((-forward成分)を90度回転させた向き)にずらす
     */
    private final double aimLateralOffset;

    public ModelConfig(Material material, int customModelData, float scale, double yOffset, double forwardOffset,
                        double aimVerticalOffset, double aimLateralOffset) {
        this.material = material;
        this.customModelData = customModelData;
        this.scale = scale;
        this.yOffset = yOffset;
        this.forwardOffset = forwardOffset;
        this.aimVerticalOffset = aimVerticalOffset;
        this.aimLateralOffset = aimLateralOffset;
    }

    public Material getMaterial() { return material; }
    public int getCustomModelData() { return customModelData; }
    public float getScale() { return scale; }
    public double getYOffset() { return yOffset; }
    public double getForwardOffset() { return forwardOffset; }
    public double getAimVerticalOffset() { return aimVerticalOffset; }
    public double getAimLateralOffset() { return aimLateralOffset; }
}
