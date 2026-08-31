package com.kaguya.custommobs.model;

/** mobs.yml の pet: セクション。これが無いMob定義はテイム不可 */
public class PetConfig {
    private final long tameCost;

    public PetConfig(long tameCost) {
        this.tameCost = tameCost;
    }

    public long getTameCost() { return tameCost; }
}
