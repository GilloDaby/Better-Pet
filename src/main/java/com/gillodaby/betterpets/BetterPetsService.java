package com.gillodaby.betterpets;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;

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

    private final BetterPetsConfig config;
    private final PetRepository repository;
    private final Set<String> allowedPets;
    private final Map<String, String> petModels;
    private final Map<String, String> petRoles;
    private final Map<UUID, PetState> activePets = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> followTask;

    BetterPetsService(BetterPetsConfig config, PetRepository repository) {
        this.config = config;
        this.repository = repository;
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

    void handleDisconnect(PlayerDisconnectEvent event) {
        if (event == null || event.getPlayerRef() == null) {
            return;
        }
        removePet(event.getPlayerRef());
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
        String key = type.toLowerCase();
        if (!repository.hasPet(owner.getUuid(), key)) {
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
        final String finalModelId = modelId;
        final String finalRoleId = roleId;
        world.execute(() -> spawnPetInternal(owner, world, key, finalModelId, finalRoleId));
        return true;
    }

    boolean togglePetOnWorld(PlayerRef owner, World world, String type) {
        if (owner == null || owner.getUuid() == null || world == null || type == null || type.isBlank()) {
            return false;
        }
        String key = type.trim().toLowerCase(Locale.ROOT);
        if (!repository.hasPet(owner.getUuid(), key)) {
            return false;
        }
        PetState current = activePets.get(owner.getUuid());
        if (current != null && key.equalsIgnoreCase(current.type)) {
            despawnPet(world, current);
            activePets.remove(owner.getUuid());
            return true;
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
        store.ensureComponent(petRef, Intangible.getComponentType());

        if (pet.getRole() == null && roleId != null && !roleId.isBlank()) {
            try {
                pet.setRoleName(roleId);
            } catch (Throwable ignored) {
            }
        }

        PetState state = new PetState(type, modelId, world.getName(), owner.getUuid(), pet.getUuid(), petRef, config.followDistance(), config.followStep());
        activePets.put(owner.getUuid(), state);
    }

    private void followPet(World world, PetState state) {
        if (world == null || state == null) {
            return;
        }
        Player player = resolvePlayer(world, state.ownerUuid);
        if (player == null) {
            activePets.remove(state.ownerUuid);
            despawnPet(world, state);
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity pet = store.getComponent(state.petRef, NPCEntity.getComponentType());
        if (pet == null || pet.wasRemoved()) {
            activePets.remove(state.ownerUuid);
            return;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(state.ownerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        if (pet.getRole() == null || pet.getRole().getMarkedEntitySupport() == null) {
            return;
        }
        pet.getRole().getMarkedEntitySupport().setMarkedEntity("LockedTarget", playerRef);
    }

    private void despawnPet(World world, PetState state) {
        if (world == null || state == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        try {
            store.removeEntity(state.petRef, RemoveReason.REMOVE);
        } catch (Throwable ignored) {
        }
    }

    private void removeExisting(UUID ownerUuid, World world) {
        PetState existing = activePets.remove(ownerUuid);
        if (existing != null) {
            despawnPet(world, existing);
        }
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
        return new Vector3d(pos.x + 1.5, pos.y, pos.z + 1.5);
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
        return "Common/UI/Custom/Pages/Memories/npcs/" + iconBase + ".png";
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
        final Ref<EntityStore> petRef;
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
