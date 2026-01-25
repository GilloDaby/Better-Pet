package com.gillodaby.betterpets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class BetterPetsConfig {

    private static final long MIN_INTERVAL_MS = 100L;
    private static final long DEFAULT_INTERVAL_MS = 100L;
    private static final double DEFAULT_FOLLOW_DISTANCE = 2.5;
    private static final double DEFAULT_FOLLOW_STEP = 0.15;
    private static final boolean DEFAULT_ALLOW_ANY_MODEL = true;
    private static final List<String> DEFAULT_PETS = List.of("duck", "wolf", "cat", "dog", "corgi");
    private static final Map<String, String> DEFAULT_MODELS = Map.of(
        "duck", "Duck",
        "wolf", "Wolf_White",
        "cat", "Cat",
        "dog", "Dog",
        "corgi", "Corgi"
    );
    private static final Map<String, String> DEFAULT_ROLES = Map.of(
        "duck", "BetterPets_Follower",
        "wolf", "BetterPets_Follower",
        "cat", "BetterPets_Follower",
        "dog", "BetterPets_Follower",
        "corgi", "BetterPets_Follower"
    );
    private static final String FALLBACK_CONTENT = """
                # Better Pets configuration
                # Available pet ids (cosmetic only)
                pets:
                    - duck
                    - wolf
                    - cat
                    - dog
                    - corgi

                # Model asset ids for pets (Server/Models)
                pet-models:
                    duck: "Duck"
                    wolf: "Wolf_White"
                    cat: "Cat"
                    dog: "Dog"
                    corgi: "Corgi"

                # NPC role ids (Server/NPC/Roles)
                pet-roles:
                    duck: "BetterPets_Follower"
                    wolf: "BetterPets_Follower"
                    cat: "BetterPets_Follower"
                    dog: "BetterPets_Follower"
                    corgi: "BetterPets_Follower"

                # Update interval for pet follow logic
                update-interval-ms: 100

                # Follow distance in blocks
                follow-distance: 2.5

                # Follow step per tick (blocks)
                follow-step: 0.15

                # Allow any model id (from Spawn Entity list)
                allow-any-model: true
        """;
    private static final String DEFAULT_CONTENT = loadDefaultContent();

    private final List<String> pets;
    private final long updateIntervalMs;
    private final double followDistance;
    private final double followStep;
    private final boolean allowAnyModel;
    private final Map<String, String> petModels;
    private final Map<String, String> petRoles;

    private BetterPetsConfig(List<String> pets, long updateIntervalMs, double followDistance, double followStep, boolean allowAnyModel, Map<String, String> petModels, Map<String, String> petRoles) {
        this.pets = pets;
        this.updateIntervalMs = updateIntervalMs;
        this.followDistance = followDistance;
        this.followStep = followStep;
        this.allowAnyModel = allowAnyModel;
        this.petModels = petModels;
        this.petRoles = petRoles;
    }

    private static String loadDefaultContent() {
        try (InputStream stream = BetterPetsConfig.class.getClassLoader().getResourceAsStream("config.yaml")) {
            if (stream == null) {
                return FALLBACK_CONTENT;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return FALLBACK_CONTENT;
        }
    }

    static BetterPetsConfig load(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException ignored) {
        }
        Path configPath = dataDir.resolve("config.yaml");
        if (Files.notExists(configPath)) {
            try {
                Files.writeString(configPath, DEFAULT_CONTENT, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        List<String> pets = new ArrayList<>(DEFAULT_PETS);
        long interval = DEFAULT_INTERVAL_MS;
        double distance = DEFAULT_FOLLOW_DISTANCE;
        double step = DEFAULT_FOLLOW_STEP;
        boolean allowAnyModel = DEFAULT_ALLOW_ANY_MODEL;
        Map<String, String> models = new HashMap<>(DEFAULT_MODELS);
        Map<String, String> roles = new HashMap<>(DEFAULT_ROLES);
        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            boolean inPets = false;
            boolean inModels = false;
            boolean inRoles = false;
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("pets:")) {
                    inPets = true;
                    inModels = false;
                    inRoles = false;
                    pets.clear();
                    continue;
                }
                if (trimmed.startsWith("pet-models:")) {
                    inModels = true;
                    inPets = false;
                    inRoles = false;
                    models.clear();
                    continue;
                }
                if (trimmed.startsWith("pet-roles:")) {
                    inRoles = true;
                    inPets = false;
                    inModels = false;
                    roles.clear();
                    continue;
                }
                if (inPets) {
                    if (!trimmed.startsWith("-")) {
                        inPets = false;
                    } else {
                        String value = trimmed.substring(1).trim().toLowerCase(Locale.ROOT);
                        if (!value.isEmpty()) {
                            pets.add(value);
                        }
                        continue;
                    }
                }
                if (inModels) {
                    int colon = trimmed.indexOf(':');
                    if (colon < 1) {
                        continue;
                    }
                    String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = trimmed.substring(colon + 1).trim();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!key.isEmpty() && !value.isEmpty()) {
                        models.put(key, value);
                    }
                    continue;
                }
                if (inRoles) {
                    int colon = trimmed.indexOf(':');
                    if (colon < 1) {
                        continue;
                    }
                    String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = trimmed.substring(colon + 1).trim();
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!key.isEmpty() && !value.isEmpty()) {
                        roles.put(key, value);
                    }
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon < 1) {
                    continue;
                }
                String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(colon + 1).trim();
                switch (key) {
                    case "update-interval-ms" -> {
                        try {
                            interval = Long.parseLong(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    case "follow-distance" -> {
                        try {
                            distance = Double.parseDouble(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    case "follow-step" -> {
                        try {
                            step = Double.parseDouble(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    case "allow-any-model" -> allowAnyModel = value.equalsIgnoreCase("true");
                    default -> {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        if (pets.isEmpty()) {
            pets = new ArrayList<>(DEFAULT_PETS);
        }
        if (models.isEmpty()) {
            models = new HashMap<>(DEFAULT_MODELS);
        }
        if (roles.isEmpty()) {
            roles = new HashMap<>(DEFAULT_ROLES);
        }
        long clamped = Math.max(MIN_INTERVAL_MS, interval);
        return new BetterPetsConfig(List.copyOf(pets), clamped, distance, step, allowAnyModel, Map.copyOf(models), Map.copyOf(roles));
    }

    List<String> pets() {
        return pets;
    }

    long updateIntervalMs() {
        return updateIntervalMs;
    }

    double followDistance() {
        return followDistance;
    }

    double followStep() {
        return followStep;
    }

    boolean allowAnyModel() {
        return allowAnyModel;
    }

    Map<String, String> petModels() {
        return petModels;
    }

    Map<String, String> petRoles() {
        return petRoles;
    }
}
