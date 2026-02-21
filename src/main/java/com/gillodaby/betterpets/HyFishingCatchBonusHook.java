package com.gillodaby.betterpets;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

final class HyFishingCatchBonusHook {

    private final BetterPetsService service;
    private final Object subscription;
    private final Method unsubscribeMethod;

    private HyFishingCatchBonusHook(BetterPetsService service, Object subscription, Method unsubscribeMethod) {
        this.service = service;
        this.subscription = subscription;
        this.unsubscribeMethod = unsubscribeMethod;
    }

    static HyFishingCatchBonusHook tryRegister(BetterPetsService service) {
        if (service == null) {
            return null;
        }
        try {
            Class<?> eventsClass = Class.forName("com.hyfishing.api.HyFishingEvents");
            Method onFishCaught = eventsClass.getMethod("onFishCaught", Consumer.class);
            Consumer<Object> consumer = event -> handleFishCaught(service, event);
            Object subscription = onFishCaught.invoke(null, consumer);
            if (subscription == null) {
                return null;
            }
            Method unsubscribe = subscription.getClass().getMethod("unsubscribe");
            unsubscribe.setAccessible(true);
            return new HyFishingCatchBonusHook(service, subscription, unsubscribe);
        } catch (Throwable ignored) {
            return null;
        }
    }

    void unregister() {
        if (subscription == null || unsubscribeMethod == null) {
            return;
        }
        try {
            unsubscribeMethod.invoke(subscription);
        } catch (Throwable ignored) {
        }
    }

    private static void handleFishCaught(BetterPetsService service, Object event) {
        if (service == null || event == null) {
            return;
        }
        try {
            Method getPlayerUuid = event.getClass().getMethod("getPlayerUuid");
            Method getFishItemId = event.getClass().getMethod("getFishItemId");

            Object playerUuidValue = getPlayerUuid.invoke(event);
            Object fishItemIdValue = getFishItemId.invoke(event);
            if (!(playerUuidValue instanceof UUID playerUuid) || !(fishItemIdValue instanceof String fishItemId)) {
                return;
            }
            if (fishItemId.isBlank()) {
                return;
            }

            double bonusPercent = service.getActiveFishingBonusPercent(playerUuid);
            int bonusQty = calculateBonusQuantity(1, bonusPercent);
            if (bonusQty <= 0) {
                return;
            }

            Player player = resolveOnlinePlayer(playerUuid);
            if (player == null) {
                return;
            }
            World world = player.getWorld();
            if (!service.isWorldAllowed(world)) {
                return;
            }

            world.execute(() -> {
                ItemContainer container = player.getInventory().getCombinedHotbarFirst();
                if (container == null) {
                    return;
                }
                ItemStack stack = new ItemStack(fishItemId, bonusQty);
                container.addItemStack(stack, true, false, true);
            });
        } catch (Throwable ignored) {
        }
    }

    private static Player resolveOnlinePlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref == null || !playerUuid.equals(ref.getUuid())) {
                continue;
            }
            Player player = ref.getComponent(Player.getComponentType());
            if (player != null) {
                return player;
            }
        }
        return null;
    }

    private static int calculateBonusQuantity(int base, double percent) {
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
}
