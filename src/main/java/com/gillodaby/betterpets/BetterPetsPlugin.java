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
        service = new BetterPetsService(getDataDirectory(), config, repository, nameRepository, settingsRepository, effectsConfig);
    }

    @Override
    public void start() {
        CommandManager.get().register(new BetterPetsCommand(service, effectsConfig));
        this.getEntityStoreRegistry().registerSystem(new PetBonusPickupSystem(service, effectsConfig));
        hyFishingCatchBonusHook = HyFishingCatchBonusHook.tryRegister(service);

        EventBus bus = HytaleServer.get().getEventBus();
        bus.registerGlobal(PlayerDisconnectEvent.class, service::handleDisconnect);
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
    }

    public double getMoneyBonusPercent(UUID playerUuid) {
        if (service == null || playerUuid == null) {
            return 0.0;
        }
        return service.getActiveMoneyBonusPercent(playerUuid);
    }
}
