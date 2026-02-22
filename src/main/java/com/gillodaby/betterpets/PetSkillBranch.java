package com.gillodaby.betterpets;

import java.util.Locale;

enum PetSkillBranch {
    MOB_DROPS("mob_drops", "Mob Drops"),
    MONEY("money", "Money"),
    FISHING("fishing", "Fishing"),
    FARMING("farming", "Farming");

    private final String key;
    private final String displayName;

    PetSkillBranch(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    String key() {
        return key;
    }

    String displayName() {
        return displayName;
    }

    static PetSkillBranch parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "mob", "mobs", "mobdrop", "mobdrops", "mob_drop", "mob_drops", "drops", "mobs_drops" -> MOB_DROPS;
            case "money", "coins", "eco", "economy" -> MONEY;
            case "fish", "fishing" -> FISHING;
            case "farm", "farming", "crop", "crops" -> FARMING;
            default -> null;
        };
    }
}
