package com.gillodaby.betterpets;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PetBonusPickupSystem extends EntityEventSystem<EntityStore, InteractivelyPickupItemEvent> {
    private final BetterPetsService service;
    private final PetEffectsConfig effects;

    public PetBonusPickupSystem(BetterPetsService service, PetEffectsConfig effects) {
        super(InteractivelyPickupItemEvent.class);
        this.service = service;
        this.effects = effects;
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> archetypeChunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, InteractivelyPickupItemEvent event) {
        PlayerRef playerRef = (PlayerRef) archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        UUIDComponent uuidComponent = (UUIDComponent) archetypeChunk.getComponent(index, UUIDComponent.getComponentType());

        UUID playerUuid = playerRef != null ? playerRef.getUuid() : null;
        if (playerUuid == null && uuidComponent != null) {
            playerUuid = uuidComponent.getUuid();
        }
        if (playerUuid == null) {
            return;
        }

        UUID worldUuid = playerRef != null ? playerRef.getWorldUuid() : null;
        World world;
        if (worldUuid != null) {
            world = Universe.get().getWorld(worldUuid);
        } else {
            World fallback;
            try {
                fallback = ((EntityStore) store.getExternalData()).getWorld();
            } catch (Exception e) {
                fallback = null;
            }
            world = fallback;
        }
        if (world == null || !service.isWorldAllowed(world)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String itemId = stack.getItemId();
        if (itemId == null || itemId.isBlank()) {
            return;
        }

        double bonusPercent = resolveBonusPercent(playerUuid, itemId);
        if (bonusPercent <= 0.0) {
            return;
        }

        int bonusQty = calculateBonusQuantity(stack.getQuantity(), bonusPercent);
        if (bonusQty <= 0 || playerRef == null) {
            return;
        }

        Player player = playerRef.getComponent(Player.getComponentType());
        if (player == null) {
            return;
        }

        ItemContainer container = player.getInventory().getCombinedHotbarFirst();
        ItemStackTransaction transaction = container.addItemStack(stack.withQuantity(bonusQty));
        if (transaction == null || !transaction.succeeded()) {
            return;
        }
    }

    private double resolveBonusPercent(UUID playerUuid, String itemId) {
        String lowered = itemId.toLowerCase(Locale.ROOT);
        boolean crop = isCropItem(lowered);
        boolean fish = isFishItem(lowered);
        boolean mobDrop = isMobDropItem(lowered);
        if (!crop && !fish && !mobDrop) {
            return 0.0;
        }

        String activePet = service.getActivePetId(playerUuid);
        if (activePet == null || activePet.isBlank()) {
            return 0.0;
        }

        if (crop) {
            return effects.getCropsBonusPercent(activePet);
        }
        if (fish) {
            return effects.getFishingBonusPercent(activePet);
        }
        return effects.getMobDropBonusPercent(activePet);
    }

    private boolean isCropItem(String lowered) {
        return lowered.contains("plant_crop")
            || lowered.contains("crop_")
            || lowered.contains("plant_fruit")
            || lowered.contains("fruit_")
            || lowered.contains("plant_seed")
            || lowered.contains("seed_");
    }

    private boolean isFishItem(String lowered) {
        return lowered.contains("fish_")
            || lowered.contains("_fish")
            || lowered.contains("fishing");
    }

    private boolean isMobDropItem(String lowered) {
        return lowered.contains("mob_")
            || lowered.contains("monster_")
            || lowered.contains("_monster")
            || lowered.contains("_bone")
            || lowered.contains("bone_")
            || lowered.contains("hide")
            || lowered.contains("fang")
            || lowered.contains("claw")
            || lowered.contains("meat")
            || lowered.contains("slime");
    }

    private int calculateBonusQuantity(int base, double percent) {
        if (base <= 0 || percent <= 0.0) {
            return 0;
        }
        double raw = base * (percent / 100.0);
        int extra = (int) Math.floor(raw);
        double fractional = raw - extra;
        if (fractional > 0.0 && ThreadLocalRandom.current().nextDouble() < fractional) {
            extra += 1;
        }
        return extra;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.of((ComponentType) PlayerRef.getComponentType());
    }
}