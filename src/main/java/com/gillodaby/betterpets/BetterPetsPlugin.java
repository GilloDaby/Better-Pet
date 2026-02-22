package com.gillodaby.betterpets;

import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.UUID;

public final class BetterPetsPlugin extends JavaPlugin {

    private BetterPetsService service;
    private PetEffectsConfig effectsConfig;
    private HyFishingCatchBonusHook hyFishingCatchBonusHook;
    private TheEconomyBridge economyBridge;
    private PetTradeManager tradeManager;

    public BetterPetsPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        BetterPetsConfig config = BetterPetsConfig.load(getDataDirectory());
        effectsConfig = PetEffectsConfig.load(getDataDirectory());
        PetRepository repository = new PetRepository(getDataDirectory());
        repository.load();
        PetNameRepository nameRepository = new PetNameRepository(getDataDirectory());
        nameRepository.load();
        PetSettingsRepository settingsRepository = new PetSettingsRepository(getDataDirectory());
        settingsRepository.load();
        PetProgressionRepository progressionRepository = new PetProgressionRepository(getDataDirectory());
        progressionRepository.load();
        service = new BetterPetsService(
            getDataDirectory(),
            config,
            repository,
            nameRepository,
            settingsRepository,
            effectsConfig,
            progressionRepository
        );
        economyBridge = new TheEconomyBridge();
        tradeManager = new PetTradeManager(service, economyBridge);
    }

    @Override
    public void start() {
        if (economyBridge != null) {
            boolean connected = economyBridge.connect();
            System.out.println("[BetterPets] TheEconomy connection: " + (connected ? "OK" : "Unavailable"));
        }

        CommandManager.get().register(new BetterPetsCommand(service, effectsConfig, tradeManager));
        this.getEntityStoreRegistry().registerSystem(new PetBonusPickupSystem(service));
        this.getEntityStoreRegistry().registerSystem(new PetMobKillXpSystem(service));
        this.getEntityStoreRegistry().registerSystem(new PetFarmingUseBlockXpSystem(service));
        this.getEntityStoreRegistry().registerSystem(new PetFarmingBreakBlockXpSystem(service));
        hyFishingCatchBonusHook = HyFishingCatchBonusHook.tryRegister(service);

        EventBus bus = HytaleServer.get().getEventBus();
        bus.registerGlobal(PlayerDisconnectEvent.class, service::handleDisconnect);
        bus.registerGlobal(PlayerDisconnectEvent.class, event -> {
            if (tradeManager == null || event == null || event.getPlayerRef() == null) {
                return;
            }
            UUID playerUuid = event.getPlayerRef().getUuid();
            if (playerUuid != null) {
                tradeManager.handleDisconnect(playerUuid);
            }
        });
        bus.registerGlobal(PlayerReadyEvent.class, service::handlePlayerReady);

        service.start();
        System.out.println("[BetterPets] Started.");
    }

    @Override
    protected void shutdown() {
        if (hyFishingCatchBonusHook != null) {
            hyFishingCatchBonusHook.unregister();
            hyFishingCatchBonusHook = null;
        }
        if (service != null) {
            service.stop();
        }
        if (tradeManager != null) {
            tradeManager.shutdown();
        }
    }

    public double getMoneyBonusPercent(UUID playerUuid) {
        if (service == null || playerUuid == null) {
            return 0.0;
        }
        return service.getActiveMoneyBonusPercent(playerUuid);
    }
}
