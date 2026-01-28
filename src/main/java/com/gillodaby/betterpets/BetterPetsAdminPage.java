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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class BetterPetsAdminPage extends InteractiveCustomUIPage<BetterPetsAdminPage.AdminEventData> {

    private static final List<String> ROLE_OPTIONS = List.of(
        "BetterPets_Follower",
        "BetterPetsFly_Follower",
        "BetterPetsSwim_Follower",
        "BetterPetsMount_Follower",
        "BetterPetsFlyMounts_Follower",
        "BetterPetsSmall_Follower",
        "BetterPetsBig_Follower"
    );

    private final BetterPetsService service;
    private final List<String> pets;
    private String currentSearchQuery;

    BetterPetsAdminPage(PlayerRef playerRef, BetterPetsService service, List<String> pets) {
        super(playerRef, CustomPageLifetime.CanDismiss, AdminEventData.CODEC);
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
        cmd.append("Pages/BetterPetsAdminPage.ui");
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
        buildPetList(cmd, events, player);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, AdminEventData data) {
        if (data == null) {
            return;
        }
        if (data.searchQuery != null) {
            handleSearch(ref, store, data.searchQuery);
            return;
        }
        if (data.petId == null || data.roleId == null || !"SetRole".equals(data.action)) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        if (!player.hasPermission("betterpets.admin")) {
            playerRef.sendMessage(Message.raw("You do not have permission to edit pet roles."));
            return;
        }
        boolean updated = service.updatePetRole(data.petId, data.roleId);
        if (!updated) {
            playerRef.sendMessage(Message.raw("Failed to update pet role."));
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        buildPetList(cmd, events, player);
        sendUpdate(cmd, events, false);
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
        Player player = store.getComponent(ref, Player.getComponentType());
        buildPetList(cmd, events, player);
        sendUpdate(cmd, events, false);
    }

    private void buildPetList(UICommandBuilder cmd, UIEventBuilder events, Player player) {
        cmd.clear("#PetColumn0");
        cmd.clear("#PetColumn1");
        cmd.clear("#PetColumn2");

        List<String> filtered = filterBySearch(pets);
        cmd.set("#PetCount.Text", filtered.size() + " PETS");
        if (filtered.isEmpty()) {
            showEmptyMessage(cmd, "NO PETS");
            return;
        }

        int col0 = 0;
        int col1 = 0;
        int col2 = 0;
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

            cmd.append(columnId, "Pages/BetterPetsAdminEntry.ui");
            String selector = columnId + "[" + slot + "]";
            cmd.set(selector + " #PetName.Text", petId);

            String currentRole = service.getRoleForPet(petId);
            cmd.set(selector + " #PetRole.Text", currentRole == null ? "" : currentRole);

            for (String role : ROLE_OPTIONS) {
                String buttonSelector = selector + " #Role" + toRoleButtonId(role);
                applyRoleButtonStyle(cmd, buttonSelector, role.equalsIgnoreCase(currentRole));
                events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    buttonSelector,
                    EventData.of("Action", "SetRole")
                        .append("PetId", petId)
                        .append("RoleId", role)
                );
            }

            index++;
        }
    }

    private void applyRoleButtonStyle(UICommandBuilder cmd, String selector, boolean active) {
        if (active) {
            PatchStyle base = new PatchStyle().setColor(Value.of("#1f6f3d"));
            PatchStyle hovered = new PatchStyle().setColor(Value.of("#258145"));
            PatchStyle pressed = new PatchStyle().setColor(Value.of("#1a5b33"));
            cmd.setObject(selector + ".Style.Default.Background", base);
            cmd.setObject(selector + ".Style.Hovered.Background", hovered);
            cmd.setObject(selector + ".Style.Pressed.Background", pressed);
        }
    }

    private String toRoleButtonId(String roleId) {
        if (roleId == null) {
            return "";
        }
        return roleId.replace("_", "");
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

    private String escapeInline(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\"", "\\\"");
    }

    public static final class AdminEventData {
        public static final BuilderCodec<AdminEventData> CODEC = BuilderCodec.builder(
            AdminEventData.class,
            AdminEventData::new
        )
            .append(new KeyedCodec<>("PetId", Codec.STRING),
                (data, value) -> data.petId = value,
                data -> data.petId).add()
            .append(new KeyedCodec<>("RoleId", Codec.STRING),
                (data, value) -> data.roleId = value,
                data -> data.roleId).add()
            .append(new KeyedCodec<>("Action", Codec.STRING),
                (data, value) -> data.action = value,
                data -> data.action).add()
            .append(new KeyedCodec<>("@SearchQuery", Codec.STRING),
                (data, value) -> data.searchQuery = value,
                data -> data.searchQuery).add()
            .build();

        public String petId;
        public String roleId;
        public String action;
        public String searchQuery;

        public AdminEventData() {
        }
    }
}
