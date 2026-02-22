package com.gillodaby.betterpets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class PetTradePage extends InteractiveCustomUIPage<PetTradePage.TradeEventData> {
    private final UUID playerUuid;
    private final PetTradeManager manager;
    private final BetterPetsService service;
    private final TheEconomyBridge economy;
    private String currentSearchQuery;

    PetTradePage(
        PlayerRef playerRef,
        UUID playerUuid,
        PetTradeManager manager,
        BetterPetsService service,
        TheEconomyBridge economy
    ) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, TradeEventData.CODEC);
        this.playerUuid = playerUuid;
        this.manager = manager;
        this.service = service;
        this.economy = economy;
        this.currentSearchQuery = "";
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/PetTrade/PetTradePage.ui");
        render(ref, cmd, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, TradeEventData data) {
        if (data == null) {
            return;
        }
        if (data.searchQuery != null) {
            currentSearchQuery = data.searchQuery.trim().toLowerCase(Locale.ROOT);
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            render(ref, cmd, events);
            sendUpdate(cmd, events, false);
            return;
        }
        if (data.action == null) {
            return;
        }
        switch (data.action) {
            case "select" -> manager.addOfferPet(playerUuid, data.petId);
            case "remove" -> manager.removeOfferPet(playerUuid, parseInt(data.offerIndex));
            case "ready" -> manager.toggleReady(playerUuid);
            case "cancel" -> manager.cancelTrade(playerUuid, "Pet trade cancelled.");
            case "money" -> manager.adjustMoneyOffer(playerUuid, parseLong(data.moneyDelta));
            default -> {
            }
        }
    }

    private void render(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events) {
        PetTradeManager.TradeSession session = manager.getSession(playerUuid);
        if (session == null) {
            close();
            return;
        }
        UUID otherUuid = manager.getOtherPlayer(playerUuid);
        if (otherUuid == null) {
            close();
            return;
        }
        String otherName = manager.resolveName(otherUuid);
        cmd.set("#TradeTitle.Text", "Pet Trade - " + otherName);
        cmd.set("#TradeSubtitle.Text", "Offer pets and coins, then ready up.");
        cmd.set("#YourName.Text", "You");
        cmd.set("#TheirName.Text", otherName);
        cmd.set("#AvailableTitle.Text", "Select pets to offer");
        cmd.set("#SearchInput.Value", currentSearchQuery == null ? "" : currentSearchQuery);

        boolean yourReady = manager.isReady(playerUuid);
        boolean theirReady = manager.isReady(otherUuid);
        cmd.set("#YourStatus.Text", yourReady ? "READY" : "NOT READY");
        cmd.set("#TheirStatus.Text", theirReady ? "READY" : "NOT READY");
        cmd.set("#ReadyButton.Text", yourReady ? "UNREADY" : "READY");

        int remaining = manager.getCountdownRemaining(playerUuid);
        if (remaining > 0) {
            cmd.set("#CountdownLabel.Text", "Confirming in " + remaining + "s");
            cmd.set("#CountdownLabel.Visible", true);
        } else {
            cmd.set("#CountdownLabel.Text", "");
            cmd.set("#CountdownLabel.Visible", false);
        }

        List<String> yourOffer = manager.getOffer(playerUuid);
        List<String> theirOffer = manager.getOffer(otherUuid);
        populateOfferList(cmd, events, "#YourOfferList", yourOffer, true, playerUuid);
        populateOfferList(cmd, events, "#TheirOfferList", theirOffer, false, otherUuid);

        boolean moneyEnabled = economy.isConnected();
        cmd.set("#MoneyPanel.Visible", moneyEnabled);
        long yourMoney = moneyEnabled ? manager.getMoneyOffer(playerUuid) : 0L;
        long theirMoney = moneyEnabled ? manager.getMoneyOffer(otherUuid) : 0L;
        cmd.set("#YourMoney.Text", "Money: " + formatMoney(yourMoney));
        cmd.set("#TheirMoney.Text", "Money: " + formatMoney(theirMoney));
        if (moneyEnabled) {
            long maxMoney = manager.getMaxMoneyOffer(playerUuid);
            cmd.set("#MoneyAmount.Text", formatMoney(yourMoney));
            cmd.set("#MoneyBalance.Text", "Balance: " + formatMoney(maxMoney));
            cmd.set("#MoneyMinus10.Disabled", yourMoney < 10);
            cmd.set("#MoneyMinus100.Disabled", yourMoney < 100);
            cmd.set("#MoneyMinus1000.Disabled", yourMoney < 1000);
            cmd.set("#MoneyMinus10000.Disabled", yourMoney < 10000);
            cmd.set("#MoneyPlus10.Disabled", yourMoney + 10 > maxMoney);
            cmd.set("#MoneyPlus100.Disabled", yourMoney + 100 > maxMoney);
            cmd.set("#MoneyPlus1000.Disabled", yourMoney + 1000 > maxMoney);
            cmd.set("#MoneyPlus10000.Disabled", yourMoney + 10000 > maxMoney);

            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyMinus10", EventData.of("Action", "money").append("MoneyDelta", "-10"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyMinus100", EventData.of("Action", "money").append("MoneyDelta", "-100"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyMinus1000", EventData.of("Action", "money").append("MoneyDelta", "-1000"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyMinus10000", EventData.of("Action", "money").append("MoneyDelta", "-10000"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyPlus10", EventData.of("Action", "money").append("MoneyDelta", "10"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyPlus100", EventData.of("Action", "money").append("MoneyDelta", "100"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyPlus1000", EventData.of("Action", "money").append("MoneyDelta", "1000"), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyPlus10000", EventData.of("Action", "money").append("MoneyDelta", "10000"), false);
        }

        populateAvailablePets(cmd, events);

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput", EventData.of("@SearchQuery", "#SearchInput.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ReadyButton", EventData.of("Action", "ready"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton", EventData.of("Action", "cancel"), false);
    }

    private void populateOfferList(
        UICommandBuilder cmd,
        UIEventBuilder events,
        String listSelector,
        List<String> offer,
        boolean allowRemove,
        UUID ownerUuid
    ) {
        cmd.clear(listSelector);
        if (offer == null || offer.isEmpty()) {
            cmd.appendInline(
                listSelector,
                "Label { Text: \"No pets offered\"; Style: (FontSize: 12, TextColor: #8ea1b7, HorizontalAlignment: Center); Anchor: (Top: 10); }"
            );
            return;
        }
        for (int i = 0; i < offer.size(); i++) {
            String petId = offer.get(i);
            cmd.append(listSelector, "Pages/PetTrade/PetTradeOfferEntry.ui");
            String selector = listSelector + "[" + i + "]";
            cmd.set(selector + " #PetName.Text", petId == null ? "" : petId);
            PetProgressSnapshot progress = service.getPetProgress(ownerUuid, petId);
            cmd.set(selector + " #PetMeta.Text", formatTradePetMeta(progress));
            String iconPath = service.resolvePetIconPath(petId);
            if (iconPath != null && !iconPath.isBlank()) {
                cmd.setObject(selector + " #Icon.Background", new PatchStyle(Value.of(iconPath)));
            }
            if (allowRemove) {
                cmd.set(selector + " #RemoveButton.Visible", true);
                events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector + " #RemoveButton",
                    EventData.of("Action", "remove").append("OfferIndex", String.valueOf(i)),
                    false
                );
            } else {
                cmd.set(selector + " #RemoveButton.Visible", false);
            }
        }
    }

    private void populateAvailablePets(UICommandBuilder cmd, UIEventBuilder events) {
        cmd.clear("#AvailableColumn0");
        cmd.clear("#AvailableColumn1");
        cmd.clear("#AvailableColumn2");

        List<String> available = manager.getAvailablePets(playerUuid);
        List<String> filtered = filterBySearch(available);
        cmd.set("#AvailableCount.Text", filtered.size() + " AVAILABLE");
        if (filtered.isEmpty()) {
            cmd.appendInline(
                "#AvailableColumns",
                "Label #EmptyAvailable { Text: \"No tradable pets\"; Style: (FontSize: 14, TextColor: #8ea1b7, HorizontalAlignment: Center, RenderUppercase: true); Anchor: (Top: 24, Left: 0, Right: 0); }"
            );
            return;
        }

        int col0 = 0;
        int col1 = 0;
        int col2 = 0;
        for (int index = 0; index < filtered.size(); index++) {
            String petId = filtered.get(index);
            int column = index % 3;
            String columnId = switch (column) {
                case 1 -> "#AvailableColumn1";
                case 2 -> "#AvailableColumn2";
                default -> "#AvailableColumn0";
            };
            int slot = switch (column) {
                case 1 -> col1++;
                case 2 -> col2++;
                default -> col0++;
            };
            cmd.append(columnId, "Pages/PetTrade/PetTradeSelectablePetEntry.ui");
            String selector = columnId + "[" + slot + "]";
            cmd.set(selector + " #PetName.Text", petId);
            PetProgressSnapshot progress = service.getPetProgress(playerUuid, petId);
            cmd.set(selector + " #PetMeta.Text", formatTradePetMeta(progress));
            cmd.set(selector + " #PetHint.Text", "Click to add");
            String iconPath = service.resolvePetIconPath(petId);
            if (iconPath != null && !iconPath.isBlank()) {
                cmd.setObject(selector + " #Icon.Background", new PatchStyle(Value.of(iconPath)));
            }
            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #SelectButton",
                EventData.of("Action", "select").append("PetId", petId),
                false
            );
        }
    }

    private List<String> filterBySearch(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        String query = currentSearchQuery;
        if (query == null || query.isBlank()) {
            List<String> copy = new ArrayList<>(source);
            copy.sort(Comparator.naturalOrder());
            return copy;
        }
        List<String> filtered = new ArrayList<>();
        for (String petId : source) {
            if (petId == null) {
                continue;
            }
            if (petId.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(petId);
            }
        }
        filtered.sort(Comparator.naturalOrder());
        return filtered;
    }

    private String formatMoney(long amount) {
        return String.format(Locale.ROOT, "%s%d", economy.unitSymbol(), Math.max(0L, amount));
    }

    private String formatTradePetMeta(PetProgressSnapshot progress) {
        if (progress == null) {
            return "Lvl 1 | P0 | XP 0/100";
        }
        int xpTarget = Math.max(1, progress.xpToNextLevel);
        String xp = progress.level >= 50 ? "MAX" : (progress.xp + "/" + xpTarget);
        return "Lvl " + progress.level + " | P" + progress.prestige + " | XP " + xp;
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    static final class TradeEventData {
        public static final BuilderCodec<TradeEventData> CODEC = BuilderCodec.builder(
            TradeEventData.class,
            TradeEventData::new
        )
            .append(new KeyedCodec<>("Action", Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action).add()
            .append(new KeyedCodec<>("PetId", Codec.STRING),
                (data, value) -> data.petId = value,
                data -> data.petId).add()
            .append(new KeyedCodec<>("OfferIndex", Codec.STRING),
                (data, value) -> data.offerIndex = value,
                data -> data.offerIndex).add()
            .append(new KeyedCodec<>("MoneyDelta", Codec.STRING),
                (data, value) -> data.moneyDelta = value,
                data -> data.moneyDelta).add()
            .append(new KeyedCodec<>("@SearchQuery", Codec.STRING),
                (data, value) -> data.searchQuery = value,
                data -> data.searchQuery).add()
            .build();

        public String action;
        public String petId;
        public String offerIndex;
        public String moneyDelta;
        public String searchQuery;

        public TradeEventData() {
        }
    }
}
