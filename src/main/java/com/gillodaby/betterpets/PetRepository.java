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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PetRepository {

    private final Path dataFile;
    private final Map<UUID, Set<String>> pets = new ConcurrentHashMap<>();

    PetRepository(Path dataDir) {
        if (dataDir == null) {
            dataDir = Path.of("BetterPets");
        }
        this.dataFile = dataDir.resolve("pets.txt");
    }

    synchronized void load() {
        pets.clear();
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
            int split = trimmed.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String uuidText = trimmed.substring(0, split).trim();
            String list = trimmed.substring(split + 1).trim();
            if (uuidText.isEmpty() || list.isEmpty()) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Set<String> petSet = new TreeSet<>();
            for (String raw : list.split(",")) {
                String petId = normalizeId(raw);
                if (!petId.isBlank()) {
                    petSet.add(petId);
                }
            }
            if (!petSet.isEmpty()) {
                pets.put(uuid, petSet);
            }
        }
    }

    synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            Path tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (UUID uuid : pets.keySet()) {
                if (uuid != null) {
                    keys.add(uuid.toString());
                }
            }
            Collections.sort(keys);
            for (String key : keys) {
                UUID uuid = UUID.fromString(key);
                Set<String> petSet = pets.get(uuid);
                if (petSet == null || petSet.isEmpty()) {
                    continue;
                }
                lines.add(key + "=" + String.join(",", petSet));
            }
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    synchronized boolean hasPet(UUID uuid, String petId) {
        if (uuid == null || petId == null || petId.isBlank()) {
            return false;
        }
        Set<String> petSet = pets.get(uuid);
        return petSet != null && petSet.contains(normalizeId(petId));
    }

    synchronized List<String> list(UUID uuid) {
        if (uuid == null) {
            return List.of();
        }
        Set<String> petSet = pets.get(uuid);
        if (petSet == null || petSet.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(petSet);
    }

    synchronized boolean givePet(UUID uuid, String petId) {
        if (uuid == null || petId == null || petId.isBlank()) {
            return false;
        }
        String normalized = normalizeId(petId);
        if (normalized.isBlank()) {
            return false;
        }
        Set<String> petSet = pets.computeIfAbsent(uuid, ignored -> new TreeSet<>());
        boolean added = petSet.add(normalized);
        if (added) {
            save();
        }
        return added;
    }

    synchronized int giveAll(UUID uuid, List<String> petIds) {
        if (uuid == null || petIds == null || petIds.isEmpty()) {
            return 0;
        }
        Set<String> petSet = pets.computeIfAbsent(uuid, ignored -> new TreeSet<>());
        int added = 0;
        for (String petId : petIds) {
            String normalized = normalizeId(petId);
            if (!normalized.isBlank() && petSet.add(normalized)) {
                added++;
            }
        }
        if (added > 0) {
            save();
        }
        return added;
    }

    private String normalizeId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
