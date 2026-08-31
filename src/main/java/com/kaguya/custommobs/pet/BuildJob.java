package com.kaguya.custommobs.pet;

import org.bukkit.Location;

/** ペットに割り当てられた建築ジョブの進行状況 */
public class BuildJob {
    private final Blueprint blueprint;
    private final Location origin;
    private int nextIndex = 0;
    private long lastPlacedTick = -1;

    public BuildJob(Blueprint blueprint, Location origin) {
        this.blueprint = blueprint;
        this.origin = origin;
    }

    public Blueprint getBlueprint() { return blueprint; }
    public Location getOrigin() { return origin; }
    public int getNextIndex() { return nextIndex; }
    public void advance() { nextIndex++; }
    public boolean isDone() { return nextIndex >= blueprint.getBlocks().size(); }

    public boolean isReady(long nowTick, long intervalTicks) {
        return lastPlacedTick < 0 || (nowTick - lastPlacedTick) >= intervalTicks;
    }

    public void markPlaced(long nowTick) { lastPlacedTick = nowTick; }
}
