package com.gillodaby.betterpets;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BetterPetsCommand extends AbstractCommand {

    private final BetterPetsService service;
    private final RequiredArg<String> spawnPetArg;
    private final RequiredArg<PlayerRef> giveTargetArg;
    private final RequiredArg<String> givePetArg;
    private final RequiredArg<PlayerRef> giveAllTargetArg;

    BetterPetsCommand(BetterPetsService service) {
        super("pet", "Pet menu and commands");
        this.service = service;

        AbstractCommand spawn = new AbstractCommand("spawn", "Spawn one of your pets") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleSpawn(ctx);
            }
        };
        this.spawnPetArg = spawn.withRequiredArg("pet", "pet id", ArgTypes.STRING);
        spawn.requirePermission("betterpets.use");
        addSubCommand(spawn);

        AbstractCommand remove = new AbstractCommand("remove", "Despawn your pet") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleRemove(ctx);
            }
        };
        remove.requirePermission("betterpets.use");
        addSubCommand(remove);

        AbstractCommand give = new AbstractCommand("give", "Give a pet to a player") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleGive(ctx);
            }
        };
        this.giveTargetArg = give.withRequiredArg("player", "target player", ArgTypes.PLAYER_REF);
        this.givePetArg = give.withRequiredArg("pet", "pet id", ArgTypes.STRING);
        give.requirePermission("betterpets.admin");
        addSubCommand(give);

        AbstractCommand giveAll = new AbstractCommand("giveall", "Give all pets to a player") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleGiveAll(ctx);
            }
        };
        this.giveAllTargetArg = giveAll.withRequiredArg("player", "target player", ArgTypes.PLAYER_REF);
        giveAll.requirePermission("betterpets.admin");
        addSubCommand(giveAll);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext ctx) {
        return handleMenu(ctx);
    }

    private CompletableFuture<Void> handleMenu(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can open the pet menu."));
            return CompletableFuture.completedFuture(null);
        }
        Player player = ctx.senderAs(Player.class);
        if (player == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || playerRef.getUuid() == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        World world = player.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        List<String> pets = service.getOwnedPets(playerRef.getUuid());
        world.execute(() -> {
            Player resolved = playerRef.getComponent(Player.getComponentType());
            if (resolved == null || resolved.getPageManager() == null) {
                playerRef.sendMessage(Message.raw("Player not available."));
                return;
            }
            PageManager pageManager = resolved.getPageManager();
            Ref<EntityStore> ref = playerRef.getReference();
            pageManager.openCustomPage(ref, ref.getStore(), new PetMenuPage(playerRef, service, pets));
        });
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleSpawn(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can spawn pets."));
            return CompletableFuture.completedFuture(null);
        }
        Player player = ctx.senderAs(Player.class);
        if (player == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || playerRef.getUuid() == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        World world = player.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        String petId = ctx.get(spawnPetArg);
        boolean spawned = service.spawnPet(playerRef, world, petId);
        if (!spawned) {
            ctx.sendMessage(Message.raw("Pet not owned or invalid: " + petId));
            return CompletableFuture.completedFuture(null);
        }
        ctx.sendMessage(Message.raw("Pet spawned: " + petId));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleRemove(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can remove pets."));
            return CompletableFuture.completedFuture(null);
        }
        Player player = ctx.senderAs(Player.class);
        if (player == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || playerRef.getUuid() == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        service.removePet(playerRef);
        ctx.sendMessage(Message.raw("Pet removed."));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleGive(CommandContext ctx) {
        PlayerRef target = ctx.get(giveTargetArg);
        if (target == null || target.getUuid() == null) {
            ctx.sendMessage(Message.raw("Target player not found."));
            return CompletableFuture.completedFuture(null);
        }
        String petId = ctx.get(givePetArg);
        boolean added = service.givePet(target, petId);
        if (!added) {
            ctx.sendMessage(Message.raw("Failed to give pet (invalid id or already owned)."));
            return CompletableFuture.completedFuture(null);
        }
        ctx.sendMessage(Message.raw("Pet given: " + petId));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleGiveAll(CommandContext ctx) {
        PlayerRef target = ctx.get(giveAllTargetArg);
        if (target == null || target.getUuid() == null) {
            ctx.sendMessage(Message.raw("Target player not found."));
            return CompletableFuture.completedFuture(null);
        }
        int added = service.giveAllPets(target);
        ctx.sendMessage(Message.raw("Pets granted: " + added));
        return CompletableFuture.completedFuture(null);
    }
}
