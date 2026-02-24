package com.gillodaby.betterpets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class PetMenuPage extends InteractiveCustomUIPage<PetMenuPage.PetMenuEventData> {

    private final BetterPetsService service;
    private final List<String> pets;
    private String currentSearchQuery;

    PetMenuPage(PlayerRef playerRef, BetterPetsService service, List<String> pets) {
        super(playerRef, CustomPageLifetime.CanDismiss, PetMenuEventData.CODEC);
        this.service = service;
        List<String> resolved = new ArrayList<>();
        if (pets != null) {
            resolved.addAll(pets);
        }
        resolved.sort(Comparator.naturalOrder());
        this.pets = resolved;
        this.currentSearchQuery = "";
    }

    @Override
    public void build(Ref<EntityStore> ref,
                      UICommandBuilder cmd,
                      UIEventBuilder events,
                      Store<EntityStore> store) {
        cmd.append("Pages/BetterPetsPage.ui");
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            showError(cmd, "PLAYER NOT FOUND");
            return;
        }
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#SearchInput",
            EventData.of("@SearchQuery", "#SearchInput.Value"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#AutoRespawnToggle #CheckBox",
            EventData.of("AutoRespawnToggle", "toggle"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#HidePetVisualToggle #CheckBox",
            EventData.of("HidePetVisualToggle", "toggle"),
            false
        );
        cmd.set("#AutoRespawnToggle #CheckBox.Value", service.isAutoRespawn(playerRef.getUuid()));
        cmd.set("#HidePetVisualToggle #CheckBox.Value", service.isHideActivePetVisual(playerRef.getUuid()));
        buildPetList(cmd, events, player);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PetMenuEventData data) {
        if (data == null) {
            return;
        }
        if (data.searchQuery != null) {
            handleSearch(ref, store, data.searchQuery);
            return;
        }
        if (data.autoRespawnToggle != null) {
            boolean current = service.isAutoRespawn(playerRef.getUuid());
            service.setAutoRespawn(playerRef.getUuid(), !current);
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#SearchInput",
                EventData.of("@SearchQuery", "#SearchInput.Value"),
                false
            );
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#AutoRespawnToggle #CheckBox",
                EventData.of("AutoRespawnToggle", "toggle"),
                false
            );
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#HidePetVisualToggle #CheckBox",
                EventData.of("HidePetVisualToggle", "toggle"),
                false
            );
            cmd.set("#AutoRespawnToggle #CheckBox.Value", !current);
            cmd.set("#HidePetVisualToggle #CheckBox.Value", service.isHideActivePetVisual(playerRef.getUuid()));
            Player player = store.getComponent(ref, Player.getComponentType());
            buildPetList(cmd, events, player);
            sendUpdate(cmd, events, false);
            return;
        }
        if (data.hidePetVisualToggle != null) {
            boolean current = service.isHideActivePetVisual(playerRef.getUuid());
            boolean next = !current;
            Player player = store.getComponent(ref, Player.getComponentType());
            World world = player == null ? null : player.getWorld();
            if (world != null) {
                world.execute(() -> service.setHideActivePetVisual(playerRef, world, next));
            } else {
                service.setHideActivePetVisual(playerRef.getUuid(), next);
            }
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#SearchInput",
                EventData.of("@SearchQuery", "#SearchInput.Value"),
                false
            );
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#AutoRespawnToggle #CheckBox",
                EventData.of("AutoRespawnToggle", "toggle"),
                false
            );
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#HidePetVisualToggle #CheckBox",
                EventData.of("HidePetVisualToggle", "toggle"),
                false
            );
            cmd.set("#AutoRespawnToggle #CheckBox.Value", service.isAutoRespawn(playerRef.getUuid()));
            cmd.set("#HidePetVisualToggle #CheckBox.Value", next);
            buildPetList(cmd, events, player);
            sendUpdate(cmd, events, false);
            return;
        }
        if (data.petId == null || !"Spawn".equals(data.action)) {
            if (data.petId != null && "Manage".equals(data.action)) {
                openUpgradePage(ref, store, data.petId);
            }
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (!player.hasPermission("betterpets.use")) {
            playerRef.sendMessage(Message.raw("You do not have permission to spawn pets."));
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        world.execute(() -> {
            service.togglePetOnWorld(playerRef, world, data.petId);
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#SearchInput",
                EventData.of("@SearchQuery", "#SearchInput.Value"),
                false
            );
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#AutoRespawnToggle #CheckBox",
                EventData.of("AutoRespawnToggle", "toggle"),
                false
            );
            events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#HidePetVisualToggle #CheckBox",
                EventData.of("HidePetVisualToggle", "toggle"),
                false
            );
            cmd.set("#AutoRespawnToggle #CheckBox.Value", service.isAutoRespawn(playerRef.getUuid()));
            cmd.set("#HidePetVisualToggle #CheckBox.Value", service.isHideActivePetVisual(playerRef.getUuid()));
            buildPetList(cmd, events, player);
            sendUpdate(cmd, events, false);
        });
    }

    private void handleSearch(Ref<EntityStore> ref, Store<EntityStore> store, String query) {
        currentSearchQuery = query == null ? "" : query.trim().toLowerCase();
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#SearchInput",
            EventData.of("@SearchQuery", "#SearchInput.Value"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#AutoRespawnToggle #CheckBox",
            EventData.of("AutoRespawnToggle", "toggle"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#HidePetVisualToggle #CheckBox",
            EventData.of("HidePetVisualToggle", "toggle"),
            false
        );
        cmd.set("#AutoRespawnToggle #CheckBox.Value", service.isAutoRespawn(playerRef.getUuid()));
        cmd.set("#HidePetVisualToggle #CheckBox.Value", service.isHideActivePetVisual(playerRef.getUuid()));
        Player player = store.getComponent(ref, Player.getComponentType());
        buildPetList(cmd, events, player);
        sendUpdate(cmd, events, false);
    }

    private void buildPetList(UICommandBuilder cmd, UIEventBuilder events, Player player) {
        cmd.clear("#PetColumn0");
        cmd.clear("#PetColumn1");
        cmd.clear("#PetColumn2");

        List<String> filtered = filterBySearch(pets);
        cmd.set("#PetCount.Text", filtered.size() + " PETS OWNED");
        if (filtered.isEmpty()) {
            showEmptyMessage(cmd, "NO PETS OWNED");
            return;
        }

        int col0 = 0;
        int col1 = 0;
        int col2 = 0;
        String activePet = service.getActivePetId(playerRef.getUuid());
        int index = 0;
        for (String petId : filtered) {
            if (petId == null || petId.isBlank()) {
                continue;
            }
            int column = index % 3;
            String columnId = switch (column) {
                case 1 -> "#PetColumn1";
                case 2 -> "#PetColumn2";
                default -> "#PetColumn0";
            };
            int slot = switch (column) {
                case 1 -> col1++;
                case 2 -> col2++;
                default -> col0++;
            };

            cmd.append(columnId, "Pages/BetterPetsEntry.ui");
            String selector = columnId + "[" + slot + "]";
            cmd.set(selector + " #PetName.Text", petId);
            cmd.set(selector + " #PetCommand.Text", buildProgressText(petId));
            cmd.set(selector + " #PetEffects.Text", buildEffectsText(petId));

            boolean isActive = activePet != null && activePet.equalsIgnoreCase(petId);
            applyPetCardStyle(cmd, selector + " #SpawnButton", isActive);

            String iconPath = service.resolvePetIconPath(petId);
            if (iconPath != null && !iconPath.isBlank()) {
                PatchStyle iconStyle = new PatchStyle(Value.of(iconPath));
                cmd.setObject(selector + " #Icon.Background", iconStyle);
                cmd.set(selector + " #Icon.Visible", true);
            } else {
                cmd.set(selector + " #Icon.Visible", false);
            }

            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #SpawnButton",
                EventData.of("Action", "Spawn").append("PetId", petId)
            );
            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #UpgradeButton",
                EventData.of("Action", "Manage").append("PetId", petId),
                false
            );
            index++;
        }
    }

    private void openUpgradePage(Ref<EntityStore> ref, Store<EntityStore> store, String petId) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getPageManager() == null) {
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new PetUpgradePage(playerRef, service, petId));
    }

    private void applyPetCardStyle(UICommandBuilder cmd, String selector, boolean active) {
        if (active) {
            PatchStyle base = new PatchStyle().setColor(Value.of("#1f6f3d"));
            PatchStyle hovered = new PatchStyle().setColor(Value.of("#258145"));
            PatchStyle pressed = new PatchStyle().setColor(Value.of("#1a5b33"));
            cmd.setObject(selector + ".Style.Default.Background", base);
            cmd.setObject(selector + ".Style.Hovered.Background", hovered);
            cmd.setObject(selector + ".Style.Pressed.Background", pressed);
        } else {
            PatchStyle base = new PatchStyle().setColor(Value.of("#161e2b"));
            PatchStyle hovered = new PatchStyle().setColor(Value.of("#1e2938"));
            PatchStyle pressed = new PatchStyle().setColor(Value.of("#131a25"));
            cmd.setObject(selector + ".Style.Default.Background", base);
            cmd.setObject(selector + ".Style.Hovered.Background", hovered);
            cmd.setObject(selector + ".Style.Pressed.Background", pressed);
        }
    }

    private List<String> filterBySearch(List<String> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        String query = currentSearchQuery;
        if (query == null || query.isBlank()) {
            return source;
        }
        List<String> filtered = new ArrayList<>();
        for (String petId : source) {
            if (petId == null) {
                continue;
            }
            if (petId.toLowerCase().contains(query)) {
                filtered.add(petId);
            }
        }
        return filtered;
    }

    private void showError(UICommandBuilder cmd, String message) {
        String ui = "Label #EmptyLabel { Text: \"" + escapeInline(message)
            + "\"; Style: (FontSize: 16, TextColor: #c76b6b, HorizontalAlignment: Center, RenderUppercase: true);"
            + " Anchor: (Top: 50); }";
        cmd.appendInline("#PetColumns", ui);
    }

    private void showEmptyMessage(UICommandBuilder cmd, String message) {
        String ui = "Label #EmptyLabel { Text: \"" + escapeInline(message)
            + "\"; Style: (FontSize: 14, TextColor: #7a8fa8, HorizontalAlignment: Center, RenderUppercase: true);"
            + " Anchor: (Left: 0, Right: 0, Top: 140); }";
        cmd.appendInline("#PetListContainer", ui);
    }

    private String buildEffectsText(String petId) {
        if (petId == null || petId.isBlank()) {
            return "No bonus";
        }
        UUID ownerUuid = playerRef.getUuid();
        List<String> parts = new ArrayList<>();
        double mob = service.getPetMobDropBonusPercent(ownerUuid, petId);
        if (mob > 0.0) {
            parts.add("Mob +" + formatPercent(mob) + "%");
        }
        double money = service.getPetMoneyBonusPercent(ownerUuid, petId);
        if (money > 0.0) {
            parts.add("Money +" + formatPercent(money) + "%");
        }
        double crops = service.getPetFarmingBonusPercent(ownerUuid, petId);
        if (crops > 0.0) {
            parts.add("Crops +" + formatPercent(crops) + "%");
        }
        double fishing = service.getPetFishingBonusPercent(ownerUuid, petId);
        if (fishing > 0.0) {
            parts.add("Fishing +" + formatPercent(fishing) + "%");
        }
        if (parts.isEmpty()) {
            return "No bonus";
        }
        return String.join(" | ", parts);
    }

    private String buildProgressText(String petId) {
        UUID ownerUuid = playerRef.getUuid();
        PetProgressSnapshot progress = service.getPetProgress(ownerUuid, petId);
        if (progress == null) {
            return "/pet spawn " + petId;
        }
        return "Lvl " + progress.level + " | P" + progress.prestige + " | Points " + progress.unspentPoints;
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

    private String escapeInline(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\"", "\\\"");
    }

    public static final class PetMenuEventData {
        public static final BuilderCodec<PetMenuEventData> CODEC = BuilderCodec.builder(
            PetMenuEventData.class,
            PetMenuEventData::new
        )
            .append(new KeyedCodec<>("PetId", Codec.STRING),
                (data, value) -> data.petId = value,
                data -> data.petId).add()
            .append(new KeyedCodec<>("Action", Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action).add()
            .append(new KeyedCodec<>("AutoRespawnToggle", Codec.STRING),
                (data, value) -> data.autoRespawnToggle = value,
                data -> data.autoRespawnToggle).add()
            .append(new KeyedCodec<>("HidePetVisualToggle", Codec.STRING),
                (data, value) -> data.hidePetVisualToggle = value,
                data -> data.hidePetVisualToggle).add()
            .append(new KeyedCodec<>("@SearchQuery", Codec.STRING),
                (data, value) -> data.searchQuery = value,
                data -> data.searchQuery).add()
            .build();

        public String petId;
        public String action;
        public String autoRespawnToggle;
        public String hidePetVisualToggle;
        public String searchQuery;

        public PetMenuEventData() {
        }
    }
}
