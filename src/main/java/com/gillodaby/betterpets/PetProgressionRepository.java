package com.gillodaby.betterpets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PetProgressionRepository {
    private final Path dataFile;
    private final Map<UUID, Map<String, PetProgressData>> progression = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    PetProgressionRepository(Path dataDir) {
        if (dataDir == null) {
            dataDir = Path.of("BetterPets");
        }
        this.dataFile = dataDir.resolve("pet_progression.txt");
        this.dirty = false;
    }

    synchronized void load() {
        progression.clear();
        dirty = false;
        if (!Files.exists(dataFile)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(dataFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split(";", -1);
            if (parts.length < 10) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(parts[0].trim());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String petId = normalizePetId(parts[1]);
            if (petId.isBlank()) {
                continue;
            }
            PetProgressData data = new PetProgressData();
            data.level = parseInt(parts[2], 1);
            data.prestige = parseInt(parts[3], 0);
            data.xp = parseInt(parts[4], 0);
            data.unspentPoints = parseInt(parts[5], 0);
            data.mobPoints = parseInt(parts[6], 0);
            data.moneyPoints = parseInt(parts[7], 0);
            data.fishingPoints = parseInt(parts[8], 0);
            data.farmingPoints = parseInt(parts[9], 0);
            clamp(data);
            progression
                .computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>())
                .put(petId, data);
        }
    }

    synchronized void flush() {
        if (!dirty) {
            return;
        }
        saveInternal();
    }

    synchronized PetProgressData getOrCreate(UUID playerUuid, String petId) {
        if (playerUuid == null) {
            return new PetProgressData();
        }
        String key = normalizePetId(petId);
        if (key.isBlank()) {
            return new PetProgressData();
        }
        Map<String, PetProgressData> playerData = progression.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>());
        PetProgressData data = playerData.computeIfAbsent(key, ignored -> new PetProgressData());
        clamp(data);
        return data;
    }

    synchronized PetProgressData snapshot(UUID playerUuid, String petId) {
        PetProgressData data = getOrCreate(playerUuid, petId);
        return data.copy();
    }

    synchronized void markDirty() {
        dirty = true;
    }

    synchronized void saveNow() {
        saveInternal();
    }

    synchronized void trade(UUID playerA, List<String> offerA, UUID playerB, List<String> offerB) {
        if (playerA == null || playerB == null || playerA.equals(playerB)) {
            return;
        }

        Map<String, PetProgressData> dataA = progression.computeIfAbsent(playerA, ignored -> new ConcurrentHashMap<>());
        Map<String, PetProgressData> dataB = progression.computeIfAbsent(playerB, ignored -> new ConcurrentHashMap<>());

        boolean changed = false;
        changed |= movePetProgress(dataA, dataB, offerA);
        changed |= movePetProgress(dataB, dataA, offerB);

        if (dataA.isEmpty()) {
            progression.remove(playerA);
        }
        if (dataB.isEmpty()) {
            progression.remove(playerB);
        }
        if (changed) {
            dirty = true;
        }
    }

    synchronized boolean resetPet(UUID playerUuid, String petId) {
        if (playerUuid == null) {
            return false;
        }
        String key = normalizePetId(petId);
        if (key.isBlank()) {
            return false;
        }
        Map<String, PetProgressData> playerData = progression.get(playerUuid);
        if (playerData == null || playerData.isEmpty()) {
            return true;
        }
        boolean changed = playerData.remove(key) != null;
        if (playerData.isEmpty()) {
            progression.remove(playerUuid);
        }
        if (changed) {
            dirty = true;
        }
        return true;
    }

    private boolean movePetProgress(
        Map<String, PetProgressData> source,
        Map<String, PetProgressData> target,
        List<String> petIds
    ) {
        if (source == null || target == null || petIds == null || petIds.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String rawPetId : petIds) {
            String petId = normalizePetId(rawPetId);
            if (petId.isBlank()) {
                continue;
            }

            PetProgressData sourceData = source.remove(petId);
            if (sourceData != null) {
                target.put(petId, sourceData.copy());
                changed = true;
                continue;
            }

            // If source has no progress data, clear any stale target entry.
            if (target.remove(petId) != null) {
                changed = true;
            }
        }
        return changed;
    }

    private void saveInternal() {
        try {
            Files.createDirectories(dataFile.getParent());
            Path tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();

            List<String> playerKeys = new ArrayList<>();
            for (UUID uuid : progression.keySet()) {
                if (uuid != null) {
                    playerKeys.add(uuid.toString());
                }
            }
            Collections.sort(playerKeys);

            for (String playerKey : playerKeys) {
                UUID uuid = UUID.fromString(playerKey);
                Map<String, PetProgressData> playerData = progression.get(uuid);
                if (playerData == null || playerData.isEmpty()) {
                    continue;
                }
                List<String> pets = new ArrayList<>(playerData.keySet());
                Collections.sort(pets);
                for (String petId : pets) {
                    PetProgressData data = playerData.get(petId);
                    if (data == null) {
                        continue;
                    }
                    clamp(data);
                    lines.add(playerKey + ";" + petId + ";"
                        + data.level + ";"
                        + data.prestige + ";"
                        + data.xp + ";"
                        + data.unspentPoints + ";"
                        + data.mobPoints + ";"
                        + data.moneyPoints + ";"
                        + data.fishingPoints + ";"
                        + data.farmingPoints);
                }
            }

            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException ignored) {
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void clamp(PetProgressData data) {
        if (data == null) {
            return;
        }
        if (data.level < 1) {
            data.level = 1;
        }
        if (data.level > 50) {
            data.level = 50;
        }
        if (data.prestige < 0) {
            data.prestige = 0;
        }
        if (data.xp < 0) {
            data.xp = 0;
        }
        if (data.unspentPoints < 0) {
            data.unspentPoints = 0;
        }
        if (data.mobPoints < 0) {
            data.mobPoints = 0;
        }
        if (data.moneyPoints < 0) {
            data.moneyPoints = 0;
        }
        if (data.fishingPoints < 0) {
            data.fishingPoints = 0;
        }
        if (data.farmingPoints < 0) {
            data.farmingPoints = 0;
        }
    }

    private String normalizePetId(String petId) {
        if (petId == null) {
            return "";
        }
        return petId.trim().toLowerCase(Locale.ROOT);
    }

    static final class PetProgressData {
        int level = 1;
        int prestige = 0;
        int xp = 0;
        int unspentPoints = 0;
        int mobPoints = 0;
        int moneyPoints = 0;
        int fishingPoints = 0;
        int farmingPoints = 0;

        int getPoints(PetSkillBranch branch) {
            if (branch == null) {
                return 0;
            }
            return switch (branch) {
                case MOB_DROPS -> mobPoints;
                case MONEY -> moneyPoints;
                case FISHING -> fishingPoints;
                case FARMING -> farmingPoints;
            };
        }

        void addPoint(PetSkillBranch branch) {
            if (branch == null) {
                return;
            }
            switch (branch) {
                case MOB_DROPS -> mobPoints++;
                case MONEY -> moneyPoints++;
                case FISHING -> fishingPoints++;
                case FARMING -> farmingPoints++;
            }
        }

        void resetBranches() {
            mobPoints = 0;
            moneyPoints = 0;
            fishingPoints = 0;
            farmingPoints = 0;
        }

        PetProgressData copy() {
            PetProgressData copy = new PetProgressData();
            copy.level = level;
            copy.prestige = prestige;
            copy.xp = xp;
            copy.unspentPoints = unspentPoints;
            copy.mobPoints = mobPoints;
            copy.moneyPoints = moneyPoints;
            copy.fishingPoints = fishingPoints;
            copy.farmingPoints = farmingPoints;
            return copy;
        }
    }
}
