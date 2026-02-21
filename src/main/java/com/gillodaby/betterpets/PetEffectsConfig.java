package com.gillodaby.betterpets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class PetEffectsConfig {
    private static final String FALLBACK_CONTENT = """
            # Pet effects configuration
            # Example: 100 = +100% (2x total), 50 = +50%
            pet-effects:
              duck:
                crops-bonus-percent: 100.0
              wolf:
                fishing-bonus-percent: 50.0
                            bee:
                                money-bonus-percent: 1.5
                            tiger:
                                mob-drop-bonus-percent: 40.0
            """;

    private static final String DEFAULT_CONTENT = loadDefaultContent();

    private final Path configPath;
    private final Map<String, Double> mobDropBonusPercent = new HashMap<>();
    private final Map<String, Double> moneyBonusPercent = new HashMap<>();
    private final Map<String, Double> cropsBonusPercent = new HashMap<>();
    private final Map<String, Double> fishingBonusPercent = new HashMap<>();

    private PetEffectsConfig(Path configPath) {
        this.configPath = configPath;
    }

    private static String loadDefaultContent() {
        try (InputStream stream = PetEffectsConfig.class.getClassLoader().getResourceAsStream("pets_effects.yaml")) {
            if (stream == null) {
                return FALLBACK_CONTENT;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return FALLBACK_CONTENT;
        }
    }

    public static PetEffectsConfig load(Path dataDir) {
        Path configPath = dataDir.resolve("pets_effects.yaml");
        PetEffectsConfig config = new PetEffectsConfig(configPath);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException ignored) {
        }
        if (Files.notExists(configPath)) {
            try {
                Files.writeString(configPath, DEFAULT_CONTENT, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        config.stripLegacyLines(configPath);
        config.read(configPath);
        return config;
    }

    public void reload() {
        mobDropBonusPercent.clear();
        moneyBonusPercent.clear();
        cropsBonusPercent.clear();
        fishingBonusPercent.clear();
        stripLegacyLines(configPath);
        read(configPath);
    }

    public double getMobDropBonusPercent(String petId) {
        if (petId == null || petId.isBlank()) {
            return 0.0;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        return mobDropBonusPercent.getOrDefault(key, 0.0);
    }

    public double getMoneyBonusPercent(String petId) {
        if (petId == null || petId.isBlank()) {
            return 0.0;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        return moneyBonusPercent.getOrDefault(key, 0.0);
    }

    public double getCropsBonusPercent(String petId) {
        if (petId == null || petId.isBlank()) {
            return 0.0;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        return cropsBonusPercent.getOrDefault(key, 0.0);
    }

    public double getFishingBonusPercent(String petId) {
        if (petId == null || petId.isBlank()) {
            return 0.0;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        return fishingBonusPercent.getOrDefault(key, 0.0);
    }

    private void read(Path path) {
        if (path == null || Files.notExists(path)) {
            return;
        }
        boolean inEffects = false;
        String currentPet = null;
        try {
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (rawLine == null) {
                    continue;
                }
                String line = rawLine.replace("\t", "  ");
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("pet-effects:")) {
                    inEffects = true;
                    currentPet = null;
                    continue;
                }
                if (!inEffects) {
                    continue;
                }
                boolean indented = line.startsWith(" ");
                if (!indented) {
                    inEffects = false;
                    currentPet = null;
                    continue;
                }
                if (line.startsWith("  ") && trimmed.endsWith(":")) {
                    currentPet = trimmed.substring(0, trimmed.length() - 1).trim().toLowerCase(Locale.ROOT);
                    continue;
                }
                if (currentPet == null || currentPet.isBlank()) {
                    continue;
                }
                if (!line.startsWith("    ")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon < 1) {
                    continue;
                }
                String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(colon + 1).trim();
                try {
                    double percent = Double.parseDouble(value);
                    switch (key) {
                        case "mob-drop-bonus-percent" -> mobDropBonusPercent.put(currentPet, percent);
                        case "money-bonus-percent" -> moneyBonusPercent.put(currentPet, percent);
                        case "crops-bonus-percent" -> cropsBonusPercent.put(currentPet, percent);
                        case "fishing-bonus-percent" -> fishingBonusPercent.put(currentPet, percent);
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void stripLegacyLines(Path path) {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try {
            java.util.List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            java.util.List<String> cleaned = new java.util.ArrayList<>(lines.size());
            boolean changed = false;
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
                if (trimmed.startsWith("xp-bonus-percent:") || trimmed.startsWith("cobble-ore-bonus-percent:")) {
                    changed = true;
                    continue;
                }
                cleaned.add(line);
            }
            if (changed) {
                Files.write(path, cleaned, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }
}