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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PetBonusPickupSystem extends EntityEventSystem<EntityStore, InteractivelyPickupItemEvent> {
    private final BetterPetsService service;
    private static final List<String> CROP_TOKENS = List.of(
        "aubergine", "berry", "carrot", "cauliflower", "chilli", "corn", "cotton",
        "health1", "health2", "health3", "lettuce", "mana1", "mana2", "mana3",
        "mushroom", "onion", "potato", "potato_sweet", "pumpkin", "rice",
        "stamina1", "stamina2", "stamina3", "tomato", "turnip", "wheat",
        "coconut", "mango", "pinkberry", "spiral", "windwillow", "melon", "beetroot"
    );

    public PetBonusPickupSystem(BetterPetsService service) {
        super(InteractivelyPickupItemEvent.class);
        this.service = service;
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

        BranchResolution resolution = resolveBonus(playerUuid, itemId);
        if (resolution.branch == PetSkillBranch.FARMING) {
            service.addFarmingActivityXp(playerUuid, 1);
        }

        double bonusPercent = resolution.bonusPercent;
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

    private BranchResolution resolveBonus(UUID playerUuid, String itemId) {
        String lowered = itemId.toLowerCase(Locale.ROOT);
        boolean crop = isCropItem(lowered);
        boolean fish = isFishItem(lowered);
        boolean mobDrop = isMobDropItem(lowered);
        if (!crop && !fish && !mobDrop) {
            return BranchResolution.none();
        }

        if (crop) {
            return new BranchResolution(PetSkillBranch.FARMING, service.getActiveFarmingBonusPercent(playerUuid));
        }
        if (fish) {
            return new BranchResolution(PetSkillBranch.FISHING, service.getActiveFishingBonusPercent(playerUuid));
        }
        return new BranchResolution(PetSkillBranch.MOB_DROPS, service.getActiveMobDropBonusPercent(playerUuid));
    }

    private boolean isCropItem(String lowered) {
        if (lowered.contains("plant_crop")
            || lowered.contains("crop_")
            || lowered.contains("plant_fruit")
            || lowered.contains("fruit_")
            || lowered.contains("plant_seed")
            || lowered.contains("seed_")) {
            return true;
        }

        for (String token : CROP_TOKENS) {
            if (containsToken(lowered, token)) {
                return true;
            }
        }
        return false;
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

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.of((ComponentType) PlayerRef.getComponentType());
    }

    private record BranchResolution(PetSkillBranch branch, double bonusPercent) {
        private static BranchResolution none() {
            return new BranchResolution(null, 0.0);
        }
    }
}
