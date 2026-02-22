package com.gillodaby.betterpets;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.HarvestingDropType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class PetFarmingUseBlockXpSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private final BetterPetsService service;
    private static final List<String> CROP_TOKENS = List.of(
        "aubergine", "berry", "carrot", "cauliflower", "chilli", "corn", "cotton",
        "health1", "health2", "health3", "lettuce", "mana1", "mana2", "mana3",
        "mushroom", "onion", "potato", "potato_sweet", "pumpkin", "rice",
        "stamina1", "stamina2", "stamina3", "tomato", "turnip", "wheat",
        "coconut", "mango", "pinkberry", "spiral", "windwillow", "melon", "beetroot"
    );

    PetFarmingUseBlockXpSystem(BetterPetsService service) {
        super(UseBlockEvent.Pre.class);
        this.service = service;
    }

    @Override
    public void handle(
        int index,
        ArchetypeChunk<EntityStore> chunk,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commandBuffer,
        UseBlockEvent.Pre event
    ) {
        if (event == null || event.isCancelled()) {
            return;
        }

        PlayerRef playerRef = (PlayerRef) chunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        Player player = playerRef.getComponent(Player.getComponentType());
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null || !service.isWorldAllowed(world)) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }
        if (!isOfficialHarvestInteraction(event, blockType)) {
            return;
        }

        service.addFarmingActivityXp(playerUuid, 1);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return (Query<EntityStore>) Archetype.of(PlayerRef.getComponentType());
    }

    private boolean isOfficialHarvestInteraction(UseBlockEvent.Pre event, BlockType blockType) {
        InteractionType interactionType = event.getInteractionType();
        InteractionContext context = event.getContext();
        if (context != null && interactionType != null) {
            String interactionId = context.getRootInteractionId(interactionType);
            String normalized = normalizeToken(interactionId);
            if (!normalized.isBlank()) {
                if (containsToken(normalized, "harvest")
                    && (containsToken(normalized, "crop") || containsToken(normalized, "farm"))) {
                    return true;
                }
                if (containsToken(normalized, "harvest") && blockType.getFarming() != null) {
                    return true;
                }
            }
        }

        if (interactionType != InteractionType.Secondary) {
            return false;
        }
        return looksLikeHarvestableCrop(blockType);
    }

    private boolean looksLikeHarvestableCrop(BlockType blockType) {
        if (blockType == null) {
            return false;
        }
        if (blockType.getFarming() != null) {
            return true;
        }
        String normalizedBlockId = normalizeToken(blockType.getId());
        if (isCropBlockId(normalizedBlockId)) {
            return true;
        }
        if (containsKnownCropToken(normalizedBlockId)) {
            return true;
        }
        BlockGathering gathering = blockType.getGathering();
        HarvestingDropType harvest = gathering == null ? null : gathering.getHarvest();
        if (harvest == null) {
            return false;
        }
        String normalizedHarvestId = normalizeToken(harvest.getItemId());
        return isCropBlockId(normalizedHarvestId) || containsKnownCropToken(normalizedHarvestId);
    }

    private boolean isCropBlockId(String lowered) {
        if (lowered == null || lowered.isBlank()) {
            return false;
        }
        return lowered.contains("plant_crop")
            || lowered.contains("crop_")
            || lowered.contains("_crop")
            || lowered.contains("plant_fruit")
            || lowered.contains("fruit_")
            || lowered.contains("_fruit")
            || lowered.contains("plant_seed")
            || lowered.contains("seed_");
    }

    private boolean containsKnownCropToken(String lowered) {
        if (lowered == null || lowered.isBlank()) {
            return false;
        }
        for (String token : CROP_TOKENS) {
            if (containsToken(lowered, token)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsToken(String value, String token) {
        if (value == null || value.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        String normalizedValue = normalizeToken(value);
        String normalizedToken = normalizeToken(token);
        if (normalizedValue.isBlank() || normalizedToken.isBlank()) {
            return false;
        }
        if (normalizedValue.equals(normalizedToken)) {
            return true;
        }
        String wrapped = "_" + normalizedValue + "_";
        return wrapped.contains("_" + normalizedToken + "_");
    }

    private String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        String lowered = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lowered.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                out.append('_');
                lastUnderscore = true;
            }
        }
        String value = out.toString();
        while (value.startsWith("_")) {
            value = value.substring(1);
        }
        while (value.endsWith("_")) {
            value = value.substring(0, value.length() - 1);
        }
        while (value.contains("__")) {
            value = value.replace("__", "_");
        }
        return value;
    }
}
