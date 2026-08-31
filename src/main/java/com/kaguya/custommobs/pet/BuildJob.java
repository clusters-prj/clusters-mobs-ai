package com.kaguya.custommobs.pet;

import org.bukkit.Location;

/**
 * ペットに割り当てられた建築ジョブの進行状況。
 * <p>
 * チャンクアンロードで本体Mobが一度「拾い直し」の対象になっても続きから再開できるよう、
 * listingId(マーケットプレイスの出品ID)を保持しておく。これがないと再開時にどの設計図か
 * 分からず、DBから取り直せない。
 */
public class BuildJob {
    private final Blueprint blueprint;
    private final Location origin;
    private final int listingId;
    private int nextIndex;
    private long lastPlacedTick = -1;

    public BuildJob(Blueprint blueprint, Location origin, int listingId) {
        this(blueprint, origin, listingId, 0);
    }

    /** チャンクアンロードなどで中断したジョブを、保存済みのnextIndexから再開する */
    public BuildJob(Blueprint blueprint, Location origin, int listingId, int startIndex) {
        this.blueprint = blueprint;
        this.origin = origin;
        this.listingId = listingId;
        this.nextIndex = startIndex;
    }

    public Blueprint getBlueprint() { return blueprint; }
    public Location getOrigin() { return origin; }
    public int getListingId() { return listingId; }
    public int getNextIndex() { return nextIndex; }
    public void advance() { nextIndex++; }
    public boolean isDone() { return nextIndex >= blueprint.getBlocks().size(); }

    public boolean isReady(long nowTick, long intervalTicks) {
        return lastPlacedTick < 0 || (nowTick - lastPlacedTick) >= intervalTicks;
    }

    public void markPlaced(long nowTick) { lastPlacedTick = nowTick; }
}
