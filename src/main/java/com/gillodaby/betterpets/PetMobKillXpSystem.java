package com.gillodaby.betterpets;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.event.KillFeedEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

final class PetMobKillXpSystem extends EntityEventSystem<EntityStore, KillFeedEvent.KillerMessage> {
    private final BetterPetsService service;

    PetMobKillXpSystem(BetterPetsService service) {
        super(KillFeedEvent.KillerMessage.class);
        this.service = service;
    }

    @Override
    public void handle(
        int index,
        ArchetypeChunk<EntityStore> chunk,
        Store<EntityStore> store,
        CommandBuffer<EntityStore> commandBuffer,
        KillFeedEvent.KillerMessage event
    ) {
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

        Ref<EntityStore> targetRef = event == null ? null : event.getTargetRef();
        if (targetRef == null || !targetRef.isValid() || targetRef.getStore() != store) {
            return;
        }
        if (isPlayerTarget(store, targetRef)) {
            return;
        }

        service.addExperienceToActivePet(playerUuid, 2, PetSkillBranch.MOB_DROPS);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return (Query<EntityStore>) Archetype.of(PlayerRef.getComponentType());
    }

    private boolean isPlayerTarget(Store<EntityStore> store, Ref<EntityStore> targetRef) {
        PlayerRef targetPlayerRef = store.getComponent(targetRef, PlayerRef.getComponentType());
        return targetPlayerRef != null && targetPlayerRef.isValid();
    }
}
