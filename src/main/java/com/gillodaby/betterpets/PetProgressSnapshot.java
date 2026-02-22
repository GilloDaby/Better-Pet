package com.gillodaby.betterpets;

final class PetProgressSnapshot {
    final String petId;
    final int level;
    final int prestige;
    final int xp;
    final int xpToNextLevel;
    final int unspentPoints;
    final int mobPoints;
    final int moneyPoints;
    final int fishingPoints;
    final int farmingPoints;
    final double mobBonusPercent;
    final double moneyBonusPercent;
    final double fishingBonusPercent;
    final double farmingBonusPercent;
    final double pointIncrementPercent;
    final double moneyPointIncrementPercent;

    PetProgressSnapshot(
        String petId,
        int level,
        int prestige,
        int xp,
        int xpToNextLevel,
        int unspentPoints,
        int mobPoints,
        int moneyPoints,
        int fishingPoints,
        int farmingPoints,
        double mobBonusPercent,
        double moneyBonusPercent,
        double fishingBonusPercent,
        double farmingBonusPercent,
        double pointIncrementPercent,
        double moneyPointIncrementPercent
    ) {
        this.petId = petId;
        this.level = level;
        this.prestige = prestige;
        this.xp = xp;
        this.xpToNextLevel = xpToNextLevel;
        this.unspentPoints = unspentPoints;
        this.mobPoints = mobPoints;
        this.moneyPoints = moneyPoints;
        this.fishingPoints = fishingPoints;
        this.farmingPoints = farmingPoints;
        this.mobBonusPercent = mobBonusPercent;
        this.moneyBonusPercent = moneyBonusPercent;
        this.fishingBonusPercent = fishingBonusPercent;
        this.farmingBonusPercent = farmingBonusPercent;
        this.pointIncrementPercent = pointIncrementPercent;
        this.moneyPointIncrementPercent = moneyPointIncrementPercent;
    }
}
