package com.kaguya.custommobs.pet;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** plugins/CustomMobs/blueprints/*.json の内容。座標は原点からの相対値 */
public class Blueprint {
    private String name;
    private List<BlockEntry> blocks;

    public String getName() { return name; }
    public List<BlockEntry> getBlocks() { return blocks; }

    public static class BlockEntry {
        private int x;
        private int y;
        private int z;
        private String material;
        /**
         * 向き・状態込みのブロックデータ文字列(例: "oak_stairs[facing=north,half=bottom]")。
         * 省略可能。指定があれば{@code material}より優先され、階段の向きや原木の軸、
         * ハーフブロックの上下など、materialだけでは表現できない状態も再現できる。
         * バニラの{@code /setblock}やF3+Iで得られる形式と同じ。
         * <p>
         * JSON側のキーはハイフン区切りの{@code block-data}(Javaの識別子にできない文字を含む)。
         * Gsonはデフォルトではフィールド名をそのまま一致させるだけでこの変換をしてくれないため、
         * {@code @SerializedName}を付けないと常にnullとして読み込まれてしまう
         * (実際にこれが原因で、指定した向きが一切反映されず全部デフォルト向きになるバグがあった)
         */
        @SerializedName("block-data")
        private String blockData;

        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public String getMaterial() { return material; }
        public String getBlockData() { return blockData; }
    }
}
