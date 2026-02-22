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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BetterPetsCommand extends AbstractCommand {

    private final BetterPetsService service;
    private final PetEffectsConfig effects;
    private final PetTradeManager tradeManager;
    private final RequiredArg<String> spawnPetArg;
    private final RequiredArg<PlayerRef> giveTargetArg;
    private final RequiredArg<String> givePetArg;
    private final RequiredArg<PlayerRef> giveAllTargetArg;
    private final RequiredArg<String> nameArg;
    private final RequiredArg<String> prestigePetArg;
    private final RequiredArg<String> xpPetArg;
    private final RequiredArg<Integer> xpAmountArg;

    BetterPetsCommand(BetterPetsService service, PetEffectsConfig effects, PetTradeManager tradeManager) {
        super("pet", "Pet menu and commands");
        this.service = service;
        this.effects = effects;
        this.tradeManager = tradeManager;

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

        AbstractCommand name = new AbstractCommand("name", "Set your pet nameplate") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleName(ctx);
            }
        };
        this.nameArg = name.withRequiredArg("name", "pet name", ArgTypes.STRING);
        name.requirePermission("betterpets.name");
        addSubCommand(name);

        AbstractCommand admin = new AbstractCommand("admin", "Open pet role admin menu") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleAdmin(ctx);
            }
        };
        admin.requirePermission("betterpets.admin");
        addSubCommand(admin);

        AbstractCommand reload = new AbstractCommand("reload", "Reload BetterPets config") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleReload(ctx);
            }
        };
        reload.requirePermission("betterpets.admin");
        addSubCommand(reload);

        AbstractCommand trade = new AbstractCommand("trade", "Trade pets with another player") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleTrade(ctx);
            }
        };
        trade.requirePermission("betterpets.use");
        trade.setAllowsExtraArguments(true);
        addSubCommand(trade);

        AbstractCommand upgrade = new AbstractCommand("upgrade", "Spend one skill point on a pet branch") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleUpgrade(ctx);
            }
        };
        upgrade.requirePermission("betterpets.use");
        upgrade.setAllowsExtraArguments(true);
        addSubCommand(upgrade);

        AbstractCommand prestige = new AbstractCommand("prestige", "Prestige a level 50 pet") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handlePrestige(ctx);
            }
        };
        this.prestigePetArg = prestige.withRequiredArg("pet", "pet id", ArgTypes.STRING);
        prestige.requirePermission("betterpets.use");
        addSubCommand(prestige);

        AbstractCommand stats = new AbstractCommand("stats", "Show pet progression stats") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleStats(ctx);
            }
        };
        stats.requirePermission("betterpets.use");
        stats.setAllowsExtraArguments(true);
        addSubCommand(stats);

        AbstractCommand xp = new AbstractCommand("addxp", "Admin: add pet experience") {
            @Override
            protected CompletableFuture<Void> execute(CommandContext ctx) {
                return handleAddXp(ctx);
            }
        };
        this.xpPetArg = xp.withRequiredArg("pet", "pet id", ArgTypes.STRING);
        this.xpAmountArg = xp.withRequiredArg("amount", "xp amount", ArgTypes.INTEGER);
        xp.requirePermission("betterpets.admin");
        addSubCommand(xp);
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
        List<String> pets = service.getVisiblePets(player, playerRef.getUuid());
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
        if (!service.isWorldAllowed(world)) {
            ctx.sendMessage(Message.raw("Pets are disabled in this world."));
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

    private CompletableFuture<Void> handleName(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can name pets."));
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
        String name = ctx.get(nameArg);
        if (name == null) {
            ctx.sendMessage(Message.raw("Usage: /pet name <text>"));
            return CompletableFuture.completedFuture(null);
        }
        world.execute(() -> service.setPetName(playerRef, world, name));
        ctx.sendMessage(Message.raw("Pet name updated."));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleAdmin(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can open the admin menu."));
            return CompletableFuture.completedFuture(null);
        }
        Player player = ctx.senderAs(Player.class);
        if (player == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        if (!player.hasPermission("betterpets.admin")) {
            ctx.sendMessage(Message.raw("You do not have permission to edit pet roles."));
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
        List<String> pets = service.getAllPets();
        world.execute(() -> {
            Player resolved = playerRef.getComponent(Player.getComponentType());
            if (resolved == null || resolved.getPageManager() == null) {
                playerRef.sendMessage(Message.raw("Player not available."));
                return;
            }
            PageManager pageManager = resolved.getPageManager();
            Ref<EntityStore> ref = playerRef.getReference();
            pageManager.openCustomPage(ref, ref.getStore(), new BetterPetsAdminPage(playerRef, service, pets));
        });
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleReload(CommandContext ctx) {
        boolean ok = service.reloadConfig();
        if (effects != null) {
            effects.reload();
        }
        ctx.sendMessage(Message.raw(ok ? "BetterPets config reloaded." : "Failed to reload BetterPets config."));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleTrade(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can use /pet trade."));
            return CompletableFuture.completedFuture(null);
        }
        Player player = ctx.senderAs(Player.class);
        if (player == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }

        List<String> args = getArgsAfter(ctx, "trade");
        if (args.isEmpty()) {
            if (tradeManager.hasSession(playerUuid)) {
                tradeManager.openTradePage(playerUuid);
            } else {
                ctx.sendMessage(Message.raw("Usage: /pet trade <player|accept|decline|cancel|demo>"));
            }
            return CompletableFuture.completedFuture(null);
        }

        String action = args.get(0);
        if ("demo".equalsIgnoreCase(action)) {
            tradeManager.openDemo(playerUuid);
            return CompletableFuture.completedFuture(null);
        }

        if ("accept".equalsIgnoreCase(action)) {
            UUID targetUuid = args.size() > 1 ? tradeManager.resolveOnlineUuid(args.get(1)) : null;
            if (targetUuid == null) {
                ctx.sendMessage(Message.raw("Player not found online."));
                return CompletableFuture.completedFuture(null);
            }
            if (!tradeManager.acceptRequest(playerUuid, targetUuid)) {
                ctx.sendMessage(Message.raw("No pending pet trade request from that player."));
            }
            return CompletableFuture.completedFuture(null);
        }

        if ("decline".equalsIgnoreCase(action) || "deny".equalsIgnoreCase(action)) {
            UUID targetUuid = args.size() > 1 ? tradeManager.resolveOnlineUuid(args.get(1)) : null;
            if (targetUuid == null) {
                ctx.sendMessage(Message.raw("Player not found online."));
                return CompletableFuture.completedFuture(null);
            }
            if (!tradeManager.declineRequest(playerUuid, targetUuid)) {
                ctx.sendMessage(Message.raw("No pending pet trade request from that player."));
            }
            return CompletableFuture.completedFuture(null);
        }

        if ("cancel".equalsIgnoreCase(action)) {
            tradeManager.cancelTrade(playerUuid, "Pet trade cancelled.");
            return CompletableFuture.completedFuture(null);
        }

        UUID targetUuid = tradeManager.resolveOnlineUuid(action);
        if (targetUuid == null) {
            ctx.sendMessage(Message.raw("Player not found online."));
            return CompletableFuture.completedFuture(null);
        }
        if (!tradeManager.sendRequest(playerUuid, targetUuid)) {
            ctx.sendMessage(Message.raw("Unable to send pet trade request right now."));
        }
        return CompletableFuture.completedFuture(null);
    }

    private List<String> getArgsAfter(CommandContext context, String subcommand) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String[] tokens = input.trim().split("\\s+");
        List<String> args = new ArrayList<>();
        boolean foundSubcommand = false;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equalsIgnoreCase(subcommand)) {
                foundSubcommand = true;
                for (int j = i + 1; j < tokens.length; j++) {
                    args.add(tokens[j]);
                }
                break;
            }
        }
        if (foundSubcommand) {
            return args;
        }
        for (String token : tokens) {
            args.add(token);
        }
        if (!args.isEmpty() && args.get(0).equalsIgnoreCase("pet")) {
            args.remove(0);
        }
        if (!args.isEmpty() && args.get(0).equalsIgnoreCase(subcommand)) {
            args.remove(0);
        }
        return args;
    }

    private CompletableFuture<Void> handleUpgrade(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can upgrade pets."));
            return CompletableFuture.completedFuture(null);
        }
        Player sender = ctx.senderAs(Player.class);
        if (sender == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        PlayerRef playerRef = sender.getPlayerRef();
        if (playerRef == null || playerRef.getUuid() == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        World world = sender.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }

        List<String> args = getArgsAfter(ctx, "upgrade");
        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                Player player = playerRef.getComponent(Player.getComponentType());
                if (player == null) {
                    playerRef.sendMessage(Message.raw("Player not available."));
                    future.complete(null);
                    return;
                }
                UUID playerUuid = player.getUuid();
                if (playerUuid == null) {
                    playerRef.sendMessage(Message.raw("Player not available."));
                    future.complete(null);
                    return;
                }

                if (args.isEmpty()) {
                    openUpgradeUi(player, service.getActivePetId(playerUuid));
                    future.complete(null);
                    return;
                }

                if (args.size() == 1) {
                    PetSkillBranch inferredBranch = PetSkillBranch.parse(args.get(0));
                    if (inferredBranch != null) {
                        String activePet = service.getActivePetId(playerUuid);
                        if (activePet == null || activePet.isBlank()) {
                            playerRef.sendMessage(Message.raw("No active pet. Use /pet spawn first or /pet upgrade <pet>."));
                            future.complete(null);
                            return;
                        }
                        upgradeBranchByCommand(playerRef, player, playerUuid, activePet, inferredBranch);
                        future.complete(null);
                        return;
                    }
                    openUpgradeUi(player, args.get(0));
                    future.complete(null);
                    return;
                }

                String petId = args.get(0);
                PetSkillBranch branch = PetSkillBranch.parse(args.get(1));
                if (petId == null || petId.isBlank() || branch == null) {
                    playerRef.sendMessage(Message.raw("Usage: /pet upgrade [pet] [mob|money|fishing|farming]"));
                    future.complete(null);
                    return;
                }
                upgradeBranchByCommand(playerRef, player, playerUuid, petId, branch);
                future.complete(null);
            } catch (Throwable t) {
                playerRef.sendMessage(Message.raw("Failed to execute /pet upgrade."));
                future.complete(null);
            }
        });
        return future;
    }

    private void upgradeBranchByCommand(
        PlayerRef playerRef,
        Player player,
        UUID playerUuid,
        String petId,
        PetSkillBranch branch
    ) {
        String normalizedPet = petId.trim().toLowerCase(Locale.ROOT);
        if (!service.ownsPet(playerUuid, normalizedPet) && !player.hasPermission("pet.owning.*")) {
            playerRef.sendMessage(Message.raw("You don't own this pet."));
            return;
        }
        boolean upgraded = service.upgradePetBranch(playerUuid, normalizedPet, branch);
        if (!upgraded) {
            PetProgressSnapshot progress = service.getPetProgress(playerUuid, normalizedPet);
            playerRef.sendMessage(Message.raw("No unspent points. Current points: " + progress.unspentPoints));
            return;
        }
        PetProgressSnapshot progress = service.getPetProgress(playerUuid, normalizedPet);
        playerRef.sendMessage(Message.raw("[Pet] Upgraded " + normalizedPet + " -> " + branch.displayName()
            + ". New bonus: " + formatPercent(resolveBranchBonus(progress, branch))
            + " | Remaining points: " + progress.unspentPoints));
    }

    private void openUpgradeUi(Player player, String preferredPetId) {
        if (player == null || player.getPageManager() == null) {
            return;
        }
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || playerRef.getReference() == null || playerRef.getReference().getStore() == null) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        player.getPageManager().openCustomPage(ref, ref.getStore(), new PetUpgradePage(playerRef, service, preferredPetId));
    }

    private CompletableFuture<Void> handlePrestige(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can prestige pets."));
            return CompletableFuture.completedFuture(null);
        }
        Player sender = ctx.senderAs(Player.class);
        if (sender == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        PlayerRef playerRef = sender.getPlayerRef();
        if (playerRef == null || playerRef.getUuid() == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        World world = sender.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }

        String petId = ctx.get(prestigePetArg);
        if (petId == null || petId.isBlank()) {
            ctx.sendMessage(Message.raw("Usage: /pet prestige <pet>"));
            return CompletableFuture.completedFuture(null);
        }
        String normalizedPet = petId.trim().toLowerCase(Locale.ROOT);
        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                Player player = playerRef.getComponent(Player.getComponentType());
                if (player == null) {
                    playerRef.sendMessage(Message.raw("Player not available."));
                    future.complete(null);
                    return;
                }
                UUID playerUuid = player.getUuid();
                if (playerUuid == null) {
                    playerRef.sendMessage(Message.raw("Player not available."));
                    future.complete(null);
                    return;
                }
                if (!service.ownsPet(playerUuid, normalizedPet) && !player.hasPermission("pet.owning.*")) {
                    playerRef.sendMessage(Message.raw("You don't own this pet."));
                    future.complete(null);
                    return;
                }
                if (!service.prestigePet(playerUuid, normalizedPet)) {
                    playerRef.sendMessage(Message.raw("This pet must be level 50 before prestiging."));
                    future.complete(null);
                    return;
                }
                PetProgressSnapshot progress = service.getPetProgress(playerUuid, normalizedPet);
                playerRef.sendMessage(Message.raw("[Pet] Prestige successful for " + normalizedPet + ". "
                    + "Prestige: " + progress.prestige
                    + " | New point values: +" + formatPercent(progress.pointIncrementPercent) + "% (Mob/Fishing/Farming)"
                    + ", +" + formatPercent(progress.moneyPointIncrementPercent) + "% (Money)"));
                future.complete(null);
            } catch (Throwable t) {
                playerRef.sendMessage(Message.raw("Failed to execute /pet prestige."));
                future.complete(null);
            }
        });
        return future;
    }

    private CompletableFuture<Void> handleStats(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can view pet stats."));
            return CompletableFuture.completedFuture(null);
        }
        Player player = ctx.senderAs(Player.class);
        if (player == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }

        String petId = resolveStatsPet(ctx, playerUuid);
        if (petId == null || petId.isBlank()) {
            ctx.sendMessage(Message.raw("Usage: /pet stats <pet> (or spawn a pet first)."));
            return CompletableFuture.completedFuture(null);
        }
        String normalizedPet = petId.trim().toLowerCase(Locale.ROOT);
        if (!service.ownsPet(playerUuid, normalizedPet) && !player.hasPermission("pet.owning.*")) {
            ctx.sendMessage(Message.raw("You don't own this pet."));
            return CompletableFuture.completedFuture(null);
        }
        PetProgressSnapshot progress = service.getPetProgress(playerUuid, normalizedPet);
        if (progress == null) {
            ctx.sendMessage(Message.raw("No progression found for this pet."));
            return CompletableFuture.completedFuture(null);
        }
        String xpLine = progress.level >= 50
            ? "MAX LEVEL"
            : (progress.xp + "/" + progress.xpToNextLevel + " XP");
        ctx.sendMessage(Message.raw("[Pet] " + normalizedPet + " | Lvl " + progress.level + " | Prestige " + progress.prestige));
        ctx.sendMessage(Message.raw("[Pet] XP: " + xpLine + " | Unspent points: " + progress.unspentPoints));
        ctx.sendMessage(Message.raw("[Pet] Mob Drops: " + formatPercent(progress.mobBonusPercent) + "% (" + progress.mobPoints + " pts)"));
        ctx.sendMessage(Message.raw("[Pet] Money: " + formatPercent(progress.moneyBonusPercent) + "% (" + progress.moneyPoints + " pts)"));
        ctx.sendMessage(Message.raw("[Pet] Fishing: " + formatPercent(progress.fishingBonusPercent) + "% (" + progress.fishingPoints + " pts)"));
        ctx.sendMessage(Message.raw("[Pet] Farming: " + formatPercent(progress.farmingBonusPercent) + "% (" + progress.farmingPoints + " pts)"));
        ctx.sendMessage(Message.raw(
            "[Pet] Point values this prestige: +" + formatPercent(progress.pointIncrementPercent)
                + "% (Mob/Fishing/Farming), +" + formatPercent(progress.moneyPointIncrementPercent) + "% (Money)"
        ));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> handleAddXp(CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("Only players can use this command."));
            return CompletableFuture.completedFuture(null);
        }
        Player player = ctx.senderAs(Player.class);
        if (player == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            ctx.sendMessage(Message.raw("Player not available."));
            return CompletableFuture.completedFuture(null);
        }
        String petId = ctx.get(xpPetArg);
        Integer amount = ctx.get(xpAmountArg);
        if (petId == null || petId.isBlank() || amount == null || amount <= 0) {
            ctx.sendMessage(Message.raw("Usage: /pet addxp <pet> <amount>"));
            return CompletableFuture.completedFuture(null);
        }
        String normalizedPet = petId.trim().toLowerCase(Locale.ROOT);
        int remaining = amount;
        PetProgressSnapshot progress = null;
        while (remaining > 0) {
            int chunk = Math.min(10, remaining);
            progress = service.addPetExperience(playerUuid, normalizedPet, chunk, null);
            remaining -= chunk;
        }
        if (progress == null) {
            ctx.sendMessage(Message.raw("Unable to apply XP to this pet."));
            return CompletableFuture.completedFuture(null);
        }
        ctx.sendMessage(Message.raw("[Pet] Added " + amount + " XP to " + normalizedPet + ". "
            + "Level: " + progress.level + " | XP: " + progress.xp + "/" + progress.xpToNextLevel));
        return CompletableFuture.completedFuture(null);
    }

    private String resolveStatsPet(CommandContext ctx, UUID playerUuid) {
        List<String> args = getArgsAfter(ctx, "stats");
        if (!args.isEmpty()) {
            return args.get(0);
        }
        return service.getActivePetId(playerUuid);
    }

    private double resolveBranchBonus(PetProgressSnapshot progress, PetSkillBranch branch) {
        if (progress == null || branch == null) {
            return 0.0;
        }
        return switch (branch) {
            case MOB_DROPS -> progress.mobBonusPercent;
            case MONEY -> progress.moneyBonusPercent;
            case FISHING -> progress.fishingBonusPercent;
            case FARMING -> progress.farmingBonusPercent;
        };
    }

    private String formatPercent(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((int) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.endsWith(".00")) {
            return text.substring(0, text.length() - 3);
        }
        if (text.endsWith("0")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }
}
