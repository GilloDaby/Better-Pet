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

final class PetNameRepository {

    private static final int MAX_NAME_LENGTH = 32;

    private final Path dataFile;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    PetNameRepository(Path dataDir) {
        if (dataDir == null) {
            dataDir = Path.of("BetterPets");
        }
        this.dataFile = dataDir.resolve("pet_names.txt");
    }

    synchronized void load() {
        names.clear();
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
            String name = trimmed.substring(split + 1).trim();
            if (uuidText.isEmpty()) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String sanitized = sanitize(name);
            if (!sanitized.isEmpty()) {
                names.put(uuid, sanitized);
            }
        }
    }

    synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            Path tempFile = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (UUID uuid : names.keySet()) {
                if (uuid != null) {
                    keys.add(uuid.toString());
                }
            }
            Collections.sort(keys);
            for (String key : keys) {
                UUID uuid = UUID.fromString(key);
                String name = names.get(uuid);
                if (name == null || name.isBlank()) {
                    continue;
                }
                lines.add(key + "=" + name);
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

    synchronized String getName(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return names.get(uuid);
    }

    synchronized void setName(UUID uuid, String name) {
        if (uuid == null) {
            return;
        }
        String sanitized = sanitize(name);
        if (sanitized.isEmpty()) {
            names.remove(uuid);
        } else {
            names.put(uuid, sanitized);
        }
        save();
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return trimmed.substring(0, MAX_NAME_LENGTH);
        }
        return trimmed;
    }
}
