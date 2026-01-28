package com.gillodaby.betterpets;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class BetterPetsService {

    private static final String DEFAULT_ICON_PATH = "Common/UI/WorldMap/MapMarkers/Player.png";
    private static final String OWN_PERMISSION_PREFIX = "pet.owning.";
    private static final String OWN_PERMISSION_ALL = "pet.owning.*";

    private final Path dataDir;
    private volatile BetterPetsConfig config;
    private final PetRepository repository;
    private final PetNameRepository nameRepository;
    private final PetSettingsRepository settingsRepository;
    private final Set<String> allowedPets;
    private final Map<String, String> petModels;
    private final Map<String, String> petRoles;
    private final Map<UUID, PetState> activePets = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> followTask;

    BetterPetsService(Path dataDir, BetterPetsConfig config, PetRepository repository, PetNameRepository nameRepository, PetSettingsRepository settingsRepository) {
        this.dataDir = dataDir;
        this.config = config;
        this.repository = repository;
        this.nameRepository = nameRepository;
        this.settingsRepository = settingsRepository;
        this.allowedPets = ConcurrentHashMap.newKeySet();
        this.allowedPets.addAll(config.pets());
        this.petModels = new ConcurrentHashMap<>(config.petModels());
        this.petRoles = new ConcurrentHashMap<>(config.petRoles());
    }

    void start() {
        if (followTask != null) {
            return;
        }
        long interval = Math.max(100L, config.updateIntervalMs());
        followTask = com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR
            .scheduleAtFixedRate(this::tickFollow, 0L, interval, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (followTask != null) {
            followTask.cancel(false);
            followTask = null;
        }
        activePets.clear();
    }

    synchronized boolean reloadConfig() {
        BetterPetsConfig newConfig = BetterPetsConfig.load(dataDir);
        if (newConfig == null) {
            return false;
        }
        this.config = newConfig;
        allowedPets.clear();
        allowedPets.addAll(newConfig.pets());
        petModels.clear();
        petModels.putAll(newConfig.petModels());
        petRoles.clear();
        petRoles.putAll(newConfig.petRoles());
        if (followTask != null) {
            followTask.cancel(false);
            followTask = null;
            start();
        }
        return true;
    }

    List<String> getAllPets() {
        return new ArrayList<>(allowedPets);
    }

    String getRoleForPet(String petId) {
        if (petId == null) {
            return null;
        }
        return petRoles.get(petId.toLowerCase(Locale.ROOT));
    }

    synchronized boolean updatePetRole(String petId, String roleId) {
        if (petId == null || roleId == null) {
            return false;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        String role = roleId.trim();
        if (key.isEmpty() || role.isEmpty()) {
            return false;
        }
        Path configPath = dataDir.resolve("config.yaml");
        List<String> lines;
        try {
            lines = new ArrayList<>(java.nio.file.Files.readAllLines(configPath, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        List<String> output = new ArrayList<>();
        boolean inRoles = false;
        boolean rolesFound = false;
        boolean updated = false;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("pet-roles:")) {
                inRoles = true;
                rolesFound = true;
                output.add(line);
                continue;
            }
            if (inRoles) {
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    output.add(line);
                    continue;
                }
                boolean indented = line.startsWith(" ") || line.startsWith("\t");
                if (!indented) {
                    if (!updated) {
                        output.add("  " + key + ": \"" + role + "\"");
                        updated = true;
                    }
                    inRoles = false;
                } else {
                    int colon = trimmed.indexOf(':');
                    if (colon > 0) {
                        String entryKey = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        if (entryKey.equals(key)) {
                            String prefix = line.substring(0, line.indexOf(trimmed));
                            output.add(prefix + entryKey + ": \"" + role + "\"");
                            updated = true;
                            continue;
                        }
                    }
                    output.add(line);
                    continue;
                }
            }
            output.add(line);
        }
        if (rolesFound && inRoles && !updated) {
            output.add("  " + key + ": \"" + role + "\"");
            updated = true;
        }
        if (!rolesFound) {
            output.add("");
            output.add("pet-roles:");
            output.add("  " + key + ": \"" + role + "\"");
        }
        try {
            java.nio.file.Files.write(configPath, output, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }
        return reloadConfig();
    }

    void handleDisconnect(PlayerDisconnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }
        removePet(event.getPlayerRef());
    }

    void handlePlayerReady(PlayerReadyEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        Player player = event.getPlayer();
        PlayerRef ref = player.getPlayerRef();
        if (ref == null || ref.getUuid() == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        if (!config.isWorldAllowed(world.getName())) {
            return;
        }
        UUID ownerUuid = ref.getUuid();
        if (!settingsRepository.isAutoRespawn(ownerUuid)) {
            return;
        }
        String lastPet = settingsRepository.getLastPet(ownerUuid);
        if (lastPet == null || lastPet.isBlank()) {
            return;
        }
        if (!repository.hasPet(ownerUuid, lastPet.toLowerCase(Locale.ROOT))) {
            return;
        }
        String modelId = resolveModelIdForPet(lastPet);
        String roleId = resolveRoleIdForPet(lastPet);
        if (modelId == null || modelId.isBlank() || roleId == null || roleId.isBlank()) {
            return;
        }
        world.execute(() -> spawnPetInternal(ref, world, lastPet.toLowerCase(Locale.ROOT), modelId, roleId));
    }

    boolean givePet(PlayerRef target, String petId) {
        if (target == null || target.getUuid() == null || petId == null || petId.isBlank()) {
            return false;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        if (!isGiftablePet(key)) {
            return false;
        }
        return repository.givePet(target.getUuid(), key);
    }

    int giveAllPets(PlayerRef target) {
        if (target == null || target.getUuid() == null) {
            return 0;
        }
        List<String> all = new ArrayList<>(allowedPets);
        if (all.isEmpty()) {
            all.addAll(petModels.keySet());
        }
        return repository.giveAll(target.getUuid(), all);
    }

    List<String> getOwnedPets(UUID ownerUuid) {
        return repository.list(ownerUuid);
    }

    List<String> getAllPetsForDisplay() {
        if (allowedPets != null && !allowedPets.isEmpty()) {
            return new ArrayList<>(allowedPets);
        }
        if (petModels != null && !petModels.isEmpty()) {
            return new ArrayList<>(petModels.keySet());
        }
        return List.of();
    }

    List<String> getVisiblePets(Player player, UUID ownerUuid) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (ownerUuid != null) {
            List<String> owned = repository.list(ownerUuid);
            if (owned != null) {
                result.addAll(owned);
            }
        }
        if (player != null) {
            if (player.hasPermission(OWN_PERMISSION_ALL)) {
                result.addAll(getAllPetsForDisplay());
            } else {
                for (String petId : getAllPetsForDisplay()) {
                    if (hasOwningPermission(player, petId)) {
                        result.add(petId);
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    boolean isWorldAllowed(World world) {
        if (world == null) {
            return false;
        }
        return config.isWorldAllowed(world.getName());
    }

    String getPetName(UUID ownerUuid) {
        return nameRepository.getName(ownerUuid);
    }

    boolean isAutoRespawn(UUID ownerUuid) {
        return settingsRepository.isAutoRespawn(ownerUuid);
    }

    void setAutoRespawn(UUID ownerUuid, boolean enabled) {
        settingsRepository.setAutoRespawn(ownerUuid, enabled);
    }

    void setPetName(PlayerRef owner, World world, String name) {
        if (owner == null || owner.getUuid() == null || world == null) {
            return;
        }
        nameRepository.setName(owner.getUuid(), name);
        PetState state = activePets.get(owner.getUuid());
        if (state == null) {
            return;
        }
        world.execute(() -> applyPetNameplate(world, state, nameRepository.getName(owner.getUuid())));
    }

    String getActivePetId(UUID ownerUuid) {
        if (ownerUuid == null) {
            return null;
        }
        PetState state = activePets.get(ownerUuid);
        return state != null ? state.type : null;
    }

    boolean spawnPet(PlayerRef owner, World world, String type) {
        if (owner == null || owner.getUuid() == null || type == null || type.isBlank()) {
            return false;
        }
        if (world == null || !config.isWorldAllowed(world.getName())) {
            return false;
        }
        String key = type.toLowerCase();
        Player player = resolvePlayer(world, owner.getUuid());
        if (player == null) {
            return false;
        }
        boolean hasPermission = hasOwningPermission(player, key);
        boolean hasPet = repository.hasPet(owner.getUuid(), key);
        if (!hasPermission && !hasPet) {
            return false;
        }
        boolean known = allowedPets.contains(key);
        String modelId = petModels.get(key);
        String roleId = petRoles.get(key);
        if (!known) {
            if (!config.allowAnyModel()) {
                return false;
            }
            modelId = resolveAnyModelId(type, key);
            roleId = "BetterPets_Follower";
        } else {
            modelId = normalizeModelId(modelId);
        }
        if (modelId == null || modelId.isBlank() || roleId == null || roleId.isBlank()) {
            return false;
        }
        settingsRepository.setLastPet(owner.getUuid(), key);
        final String finalModelId = modelId;
        final String finalRoleId = roleId;
        world.execute(() -> spawnPetInternal(owner, world, key, finalModelId, finalRoleId));
        return true;
    }

    boolean togglePetOnWorld(PlayerRef owner, World world, String type) {
        if (owner == null || owner.getUuid() == null || world == null || type == null || type.isBlank()) {
            return false;
        }
        if (!config.isWorldAllowed(world.getName())) {
            return false;
        }
        String key = type.trim().toLowerCase(Locale.ROOT);
        boolean hasPet = repository.hasPet(owner.getUuid(), key);
        PetState current = activePets.get(owner.getUuid());
        if (current != null && key.equalsIgnoreCase(current.type)) {
            despawnPet(world, current);
            activePets.remove(owner.getUuid());
            return true;
        }
        Player player = resolvePlayer(world, owner.getUuid());
        if (player == null) {
            return false;
        }
        boolean hasPermission = hasOwningPermission(player, key);
        if (!hasPermission && !hasPet) {
            return false;
        }
        boolean known = allowedPets.contains(key);
        String modelId = petModels.get(key);
        String roleId = petRoles.get(key);
        if (!known) {
            if (!config.allowAnyModel()) {
                return false;
            }
            modelId = resolveAnyModelId(type, key);
            roleId = "BetterPets_Follower";
        } else {
            modelId = normalizeModelId(modelId);
        }
        if (modelId == null || modelId.isBlank() || roleId == null || roleId.isBlank()) {
            return false;
        }
        settingsRepository.setLastPet(owner.getUuid(), key);
        spawnPetInternal(owner, world, key, modelId, roleId);
        return true;
    }

    void removePet(PlayerRef owner) {
        if (owner == null || owner.getUuid() == null) {
            return;
        }
        PetState state = activePets.remove(owner.getUuid());
        if (state == null) {
            return;
        }
        World world = resolveWorld(state.worldName);
        if (world == null) {
            return;
        }
        world.execute(() -> despawnPet(world, state));
    }

    private void tickFollow() {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        if (activePets.isEmpty()) {
            return;
        }
        for (PetState state : activePets.values()) {
            if (state == null) {
                continue;
            }
            World world = resolveWorld(state.worldName);
            if (world == null) {
                continue;
            }
            if (!config.isWorldAllowed(world.getName())) {
                World currentWorld = world;
                activePets.remove(state.ownerUuid);
                currentWorld.execute(() -> despawnPet(currentWorld, state));
                continue;
            }
            world.execute(() -> followPet(world, state));
        }
    }

    private void spawnPetInternal(PlayerRef owner, World world, String type, String modelId, String roleId) {
        if (owner == null || owner.getUuid() == null || world == null) {
            return;
        }
        Player player = resolvePlayer(world, owner.getUuid());
        if (player == null) {
            return;
        }
        removeExisting(owner.getUuid(), world);

        Vector3d spawnPos = computeSpawnPosition(player);
        Vector3f rotation = player.getTransformComponent() != null
            ? player.getTransformComponent().getRotation()
            : new Vector3f(0, 0, 0);

        NPCEntity pet = new NPCEntity(world);
        pet.setRoleName(roleId);
        world.spawnEntity(pet, spawnPos, rotation);
        Ref<EntityStore> petRef = pet.getReference();
        if (petRef == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        try {
            NPCEntity.setAppearance(petRef, modelId, store);
        } catch (Throwable ignored) {
        }
        store.ensureComponent(petRef, Invulnerable.getComponentType());

        if (pet.getRole() == null && roleId != null && !roleId.isBlank()) {
            try {
                pet.setRoleName(roleId);
            } catch (Throwable ignored) {
            }
        }

        PetState state = new PetState(type, modelId, world.getName(), owner.getUuid(), pet.getUuid(), petRef, config.followDistance(), config.followStep());
        activePets.put(owner.getUuid(), state);
        String petName = nameRepository.getName(owner.getUuid());
        if (petName != null && !petName.isBlank()) {
            applyPetNameplate(world, state, petName);
        }
    }

    private void followPet(World world, PetState state) {
        if (world == null || state == null) {
            return;
        }
        Ref<EntityStore> petRef = resolvePetRef(world, state);
        if (petRef == null || !petRef.isValid()) {
            activePets.remove(state.ownerUuid);
            return;
        }
        Player player = resolvePlayer(world, state.ownerUuid);
        if (player == null) {
            activePets.remove(state.ownerUuid);
            despawnPet(world, state);
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity pet;
        try {
            pet = store.getComponent(state.petRef, NPCEntity.getComponentType());
        } catch (RuntimeException ex) {
            activePets.remove(state.ownerUuid);
            return;
        }
        if (pet == null || pet.wasRemoved()) {
            if (tryAutoRespawn(world, state)) {
                return;
            }
            activePets.remove(state.ownerUuid);
            return;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(state.ownerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        if (pet.getRole() != null && pet.getRole().getMarkedEntitySupport() != null) {
            pet.getRole().getMarkedEntitySupport().setMarkedEntity("LockedTarget", playerRef);
        }
        TransformComponent playerTc;
        TransformComponent petTc;
        try {
            playerTc = store.getComponent(playerRef, TransformComponent.getComponentType());
            petTc = store.getComponent(petRef, TransformComponent.getComponentType());
        } catch (RuntimeException ex) {
            return;
        }
        if (playerTc == null || petTc == null) {
            return;
        }
        Vector3d playerPos = playerTc.getPosition();
        Vector3d petPos = petTc.getPosition();
        double dx = playerPos.x - petPos.x;
        double dy = playerPos.y - petPos.y;
        double dz = playerPos.z - petPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        double teleportDistance = config.teleportDistance();
        if (distSq >= teleportDistance * teleportDistance) {
            Vector3d teleportPos = computeSpawnPosition(player);
            petTc.setPosition(teleportPos);
        }
    }

    private void despawnPet(World world, PetState state) {
        if (world == null || state == null) {
            return;
        }
        Ref<EntityStore> petRef = resolvePetRef(world, state);
        if (petRef == null || !petRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        try {
            store.removeEntity(petRef, RemoveReason.REMOVE);
        } catch (Throwable ignored) {
        }
    }

    private void applyPetNameplate(World world, PetState state, String name) {
        if (world == null || state == null || name == null || name.isBlank()) {
            return;
        }
        Ref<EntityStore> petRef = resolvePetRef(world, state);
        if (petRef == null || !petRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        try {
            NPCEntity pet = store.getComponent(petRef, NPCEntity.getComponentType());
            if (pet == null || pet.wasRemoved()) {
                return;
            }
            Nameplate np = store.getComponent(petRef, Nameplate.getComponentType());
            if (np == null) {
                np = new Nameplate(name);
                applyLabelSettings(np);
                store.addComponent(petRef, Nameplate.getComponentType(), np);
            } else {
                callIfExists(np, "setText", String.class, name);
                applyLabelSettings(np);
            }
        } catch (Throwable ignored) {
        }
    }

    private Ref<EntityStore> resolvePetRef(World world, PetState state) {
        if (world == null || state == null) {
            return null;
        }
        if (state.petRef != null && state.petRef.isValid()) {
            return state.petRef;
        }
        Ref<EntityStore> ref = world.getEntityRef(state.petUuid);
        if (ref != null && ref.isValid()) {
            state.petRef = ref;
            return ref;
        }
        return state.petRef;
    }

    private void applyLabelSettings(Nameplate np) {
        if (np == null) {
            return;
        }
        callIfExists(np, "setVisible", boolean.class, true);
        callIfExists(np, "setEnabled", boolean.class, true);
        callIfExists(np, "setAlwaysVisible", boolean.class, true);
        callIfExists(np, "setMaxDistance", float.class, 9999f);
        callIfExists(np, "setMaxDistance", double.class, 9999.0);
    }

    private void callIfExists(Object target, String methodName, Class<?> argType, Object arg) {
        if (target == null || methodName == null) {
            return;
        }
        try {
            target.getClass().getMethod(methodName, argType).invoke(target, arg);
        } catch (Exception ignored) {
        }
    }

    private void removeExisting(UUID ownerUuid, World world) {
        PetState existing = activePets.remove(ownerUuid);
        if (existing != null) {
            despawnPet(world, existing);
        }
    }

    private boolean tryAutoRespawn(World world, PetState state) {
        if (world == null || state == null) {
            return false;
        }
        if (!config.isWorldAllowed(world.getName())) {
            return false;
        }
        UUID ownerUuid = state.ownerUuid;
        if (ownerUuid == null || !settingsRepository.isAutoRespawn(ownerUuid)) {
            return false;
        }
        String lastPet = settingsRepository.getLastPet(ownerUuid);
        if (lastPet == null || lastPet.isBlank()) {
            return false;
        }
        if (!repository.hasPet(ownerUuid, lastPet.toLowerCase(Locale.ROOT))) {
            return false;
        }
        Player player = resolvePlayer(world, ownerUuid);
        if (player == null) {
            return false;
        }
        PlayerRef ref = player.getPlayerRef();
        if (ref == null || ref.getUuid() == null) {
            return false;
        }
        String modelId = resolveModelIdForPet(lastPet);
        String roleId = resolveRoleIdForPet(lastPet);
        if (modelId == null || modelId.isBlank() || roleId == null || roleId.isBlank()) {
            return false;
        }
        spawnPetInternal(ref, world, lastPet.toLowerCase(Locale.ROOT), modelId, roleId);
        return true;
    }

    private String resolveModelIdForPet(String petId) {
        if (petId == null) {
            return null;
        }
        String key = petId.toLowerCase(Locale.ROOT);
        boolean known = allowedPets.contains(key);
        String modelId = petModels.get(key);
        if (!known) {
            if (!config.allowAnyModel()) {
                return null;
            }
            modelId = resolveAnyModelId(petId, key);
        } else {
            modelId = normalizeModelId(modelId);
        }
        return modelId;
    }

    private String resolveRoleIdForPet(String petId) {
        if (petId == null) {
            return null;
        }
        String key = petId.toLowerCase(Locale.ROOT);
        if (allowedPets.contains(key)) {
            return petRoles.get(key);
        }
        return config.allowAnyModel() ? "BetterPets_Follower" : null;
    }

    private Player resolvePlayer(World world, UUID uuid) {
        if (world == null || uuid == null) {
            return null;
        }
        com.hypixel.hytale.server.core.entity.Entity entity = world.getEntity(uuid);
        if (entity instanceof Player player) {
            return player;
        }
        return null;
    }

    private Vector3d computeSpawnPosition(Player player) {
        if (player == null || player.getTransformComponent() == null) {
            return new Vector3d(0, 0, 0);
        }
        Vector3d pos = player.getTransformComponent().getPosition();
        return new Vector3d(pos.x + 1.5, pos.y + 1.0, pos.z + 1.5);
    }

    String resolvePetIconPath(String petId) {
        if (petId == null || petId.isBlank()) {
            return DEFAULT_ICON_PATH;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        String modelId = resolveAnyModelId(petId, key);
        String base = normalizeModelId(modelId);
        if (base == null || base.isBlank()) {
            return DEFAULT_ICON_PATH;
        }
        String iconBase = base.startsWith("Model_") ? base.substring("Model_".length()) : base;
        String resourceExact = "Common/UI/Custom/Pages/Memories/npcs/" + iconBase + ".png";
        String uiExact = "Pages/Memories/npcs/" + iconBase + ".png";
        if (resourceExists(resourceExact)) {
            return uiExact;
        }
        String lowerName = iconBase.toLowerCase(Locale.ROOT);
        String resourceLower = "Common/UI/Custom/Pages/Memories/npcs/" + lowerName + ".png";
        String uiLower = "Pages/Memories/npcs/" + lowerName + ".png";
        if (resourceExists(resourceLower)) {
            return uiLower;
        }
        return uiExact;
    }

    private boolean resourceExists(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try (InputStream stream = BetterPetsService.class.getClassLoader().getResourceAsStream(path)) {
            return stream != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPetAllowed(String petId) {
        if (petId == null || petId.isBlank()) {
            return false;
        }
        return allowedPets.contains(petId) || config.allowAnyModel();
    }

    private boolean isGiftablePet(String petId) {
        if (petId == null || petId.isBlank()) {
            return false;
        }
        return allowedPets.contains(petId) || petModels.containsKey(petId);
    }

    boolean hasOwningPermission(Player player, String petId) {
        if (player == null || petId == null || petId.isBlank()) {
            return false;
        }
        String key = petId.trim().toLowerCase(Locale.ROOT);
        return player.hasPermission(OWN_PERMISSION_ALL) || player.hasPermission(OWN_PERMISSION_PREFIX + key);
    }


    private String resolveAnyModelId(String rawInput, String key) {
        if (rawInput == null) {
            return null;
        }
        String input = rawInput.trim();
        if (input.isEmpty()) {
            return null;
        }
        String fromMap = petModels.get(key);
        if (fromMap != null && !fromMap.isBlank()) {
            return normalizeModelId(fromMap);
        }
        String lower = input.toLowerCase();
        for (Map.Entry<String, String> entry : petModels.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            String base = normalizeModelId(value);
            if (value.equalsIgnoreCase(input) || base.equalsIgnoreCase(input) || base.toLowerCase().equals(lower)) {
                return base;
            }
        }
        return normalizeModelId(input);
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null) {
            return null;
        }
        String trimmed = modelId.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0 && slash < trimmed.length() - 1) {
            return trimmed.substring(slash + 1);
        }
        return trimmed;
    }


    private World resolveWorld(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        Map<?, World> worlds = universe.getWorlds();
        if (worlds == null || worlds.isEmpty()) {
            return null;
        }
        for (World world : worlds.values()) {
            if (world != null && name.equalsIgnoreCase(world.getName())) {
                return world;
            }
        }
        return null;
    }

    private static final class PetState {
        final String type;
        final String modelId;
        final String worldName;
        final UUID ownerUuid;
        final UUID petUuid;
        Ref<EntityStore> petRef;
        final double followDistance;
        final double followStep;

        PetState(String type, String modelId, String worldName, UUID ownerUuid, UUID petUuid, Ref<EntityStore> petRef, double followDistance, double followStep) {
            this.type = type;
            this.modelId = modelId;
            this.worldName = worldName;
            this.ownerUuid = ownerUuid;
            this.petUuid = petUuid;
            this.petRef = petRef;
            this.followDistance = followDistance;
            this.followStep = followStep;
        }
    }
}
