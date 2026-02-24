package com.gillodaby.betterpets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PetSettingsRepository {

    private final Path dataFile;
    private final Map<UUID, PetSettings> settings = new ConcurrentHashMap<>();

    PetSettingsRepository(Path dataDir) {
        if (dataDir == null) {
            dataDir = Path.of("BetterPets");
        }
        this.dataFile = dataDir.resolve("pet_settings.txt");
    }

    synchronized void load() {
        settings.clear();
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
            String data = trimmed.substring(split + 1).trim();
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String[] parts = data.split(";", -1);
            boolean auto = parts.length > 0 && "true".equalsIgnoreCase(parts[0].trim());
            String last = parts.length > 1 ? parts[1].trim() : "";
            boolean hideVisual = parts.length > 2 && "true".equalsIgnoreCase(parts[2].trim());
            settings.put(uuid, new PetSettings(auto, last, hideVisual));
        }
    }

    synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            Path tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (UUID uuid : settings.keySet()) {
                if (uuid != null) {
                    keys.add(uuid.toString());
                }
            }
            Collections.sort(keys);
            for (String key : keys) {
                UUID uuid = UUID.fromString(key);
                PetSettings data = settings.get(uuid);
                if (data == null) {
                    continue;
                }
                String last = data.lastPet == null ? "" : data.lastPet;
                lines.add(key + "=" + data.autoRespawn + ";" + last + ";" + data.hideActivePetVisual);
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

    synchronized boolean isAutoRespawn(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        PetSettings data = settings.get(uuid);
        return data != null && data.autoRespawn;
    }

    synchronized void setAutoRespawn(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return;
        }
        PetSettings data = settings.computeIfAbsent(uuid, ignored -> new PetSettings(false, "", false));
        data.autoRespawn = enabled;
        save();
    }

    synchronized String getLastPet(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        PetSettings data = settings.get(uuid);
        if (data == null || data.lastPet == null || data.lastPet.isBlank()) {
            return null;
        }
        return data.lastPet;
    }

    synchronized void setLastPet(UUID uuid, String petId) {
        if (uuid == null) {
            return;
        }
        PetSettings data = settings.computeIfAbsent(uuid, ignored -> new PetSettings(false, "", false));
        data.lastPet = petId == null ? "" : petId;
        save();
    }

    synchronized boolean isHideActivePetVisual(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        PetSettings data = settings.get(uuid);
        return data != null && data.hideActivePetVisual;
    }

    synchronized void setHideActivePetVisual(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return;
        }
        PetSettings data = settings.computeIfAbsent(uuid, ignored -> new PetSettings(false, "", false));
        data.hideActivePetVisual = enabled;
        save();
    }

    private static final class PetSettings {
        boolean autoRespawn;
        String lastPet;
        boolean hideActivePetVisual;

        PetSettings(boolean autoRespawn, String lastPet, boolean hideActivePetVisual) {
            this.autoRespawn = autoRespawn;
            this.lastPet = lastPet;
            this.hideActivePetVisual = hideActivePetVisual;
        }
    }
}
