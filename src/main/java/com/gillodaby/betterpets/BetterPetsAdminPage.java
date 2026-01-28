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
import java.util.Map;
import java.util.List;

final class BetterPetsAdminPage extends InteractiveCustomUIPage<BetterPetsAdminPage.AdminEventData> {

    private static final List<String> ROLE_OPTIONS = List.of(
        "BetterPets_Follower",
        "BetterPetsFly_Follower",
        "BetterPetsSwim_Follower",
        "BetterPetsSwimMount_Follower",
        "BetterPetsMount_Follower",
        "BetterPetsFlyMounts_Follower",
        "BetterPetsSmall_Follower",
        "BetterPetsBig_Follower",
        "BetterPetsBigMount_Follower"
    );

    private static final Map<String, String> ROLE_LABELS = Map.of(
        "betterpets_follower", "Walking Pet",
        "betterpetsfly_follower", "Flying Pet",
        "betterpetsswim_follower", "Swim Pet",
        "betterpetsswimmount_follower", "Swim Mount Pet",
        "betterpetsmount_follower", "Mount Pet",
        "betterpetsflymounts_follower", "Flying Mount Pet",
        "betterpetssmall_follower", "Small Walking Pet",
        "betterpetsbig_follower", "Big Walking Pet",
        "BetterPetsBigMount_Follower", "Big Walking Mount Pet"
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

            String iconPath = service.resolvePetIconPath(petId);
            if (iconPath != null && !iconPath.isBlank()) {
                PatchStyle iconStyle = new PatchStyle(Value.of(iconPath));
                cmd.setObject(selector + " #Icon.Background", iconStyle);
                cmd.set(selector + " #Icon.Visible", true);
            } else {
                cmd.set(selector + " #Icon.Visible", false);
            }

            buildRoleButtons(cmd, events, selector, currentRole, petId);

            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #CustomRoleApply",
                EventData.of("Action", "SetRole")
                    .append("PetId", petId)
                    .append("@RoleId", "#CustomRoleInput.Value")
            );

            index++;
        }
    }

    private void buildRoleButtons(UICommandBuilder cmd,
                                  UIEventBuilder events,
                                  String selector,
                                  String currentRole,
                                  String petId) {
        cmd.clear(selector + " #RoleGrid");
        List<String> roles = resolveRoleOptions();
        if (roles.isEmpty()) {
            return;
        }
        StringBuilder ui = new StringBuilder();
        ui.append("Group { LayoutMode: Top; ");

        int rowIndex = 0;
        int colIndex = 0;
        int rendered = 0;
        for (int i = 0; i < roles.size(); i++) {
            String role = roles.get(i);
            if (role == null || role.isBlank()) {
                continue;
            }
            if (colIndex == 0) {
                ui.append("Group #RoleRow").append(rowIndex)
                    .append(" { LayoutMode: Left; Anchor: (Height: 36); ");
            }
            if (colIndex > 0) {
                ui.append("Group { Anchor: (Width: 6); }");
            }
            String roleButtonId = "Role" + toRoleButtonId(role);
            ui.append("Button #").append(roleButtonId).append(" { ")
                .append("Style: ButtonStyle(")
                .append("Default: (Background: (Color: #1e2938)),")
                .append("Hovered: (Background: (Color: #253246)),")
                .append("Pressed: (Background: (Color: #1a2433))")
                .append(");")
                .append("FlexWeight: 1;")
                .append("Label { Text: \"").append(escapeInline(formatRoleLabel(role))).append("\"; Style: (")
                .append("FontSize: 12, TextColor: #d4dfe8, HorizontalAlignment: Center, VerticalAlignment: Center, RenderBold: true");
            ui.append("); } }");

            colIndex++;
            rendered++;
            if (colIndex >= 3) {
                ui.append("}");
                colIndex = 0;
                rowIndex++;
                if (rendered < roles.size()) {
                    ui.append("Group { Anchor: (Height: 6); }");
                }
            }
        }
        if (colIndex > 0) {
            ui.append("}");
        }
        ui.append("}");

        cmd.appendInline(selector + " #RoleGrid", ui.toString());

        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String roleButtonId = "Role" + toRoleButtonId(role);
            String buttonSelector = selector + " #" + roleButtonId;
            applyRoleButtonStyle(cmd, buttonSelector, role.equalsIgnoreCase(currentRole));
            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                buttonSelector,
                EventData.of("Action", "SetRole")
                    .append("PetId", petId)
                    .append("RoleId", role)
            );
        }
    }

    private List<String> resolveRoleOptions() {
        return ROLE_OPTIONS;
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
        return roleId.replaceAll("[^A-Za-z0-9]", "");
    }

    private String formatRoleLabel(String roleId) {
        if (roleId == null) {
            return "";
        }
        String trimmed = roleId.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String mapped = ROLE_LABELS.get(trimmed.toLowerCase());
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        if (trimmed.startsWith("BetterPets")) {
            trimmed = trimmed.substring("BetterPets".length());
        }
        trimmed = trimmed.replace("_", " ").trim();
        if (trimmed.isEmpty()) {
            return roleId;
        }
        return trimmed;
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
            .append(new KeyedCodec<>("@RoleId", Codec.STRING),
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
