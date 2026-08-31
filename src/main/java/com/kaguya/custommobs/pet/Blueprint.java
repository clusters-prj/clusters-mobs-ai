package com.kaguya.custommobs.pet;

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

        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public String getMaterial() { return material; }
    }
}
