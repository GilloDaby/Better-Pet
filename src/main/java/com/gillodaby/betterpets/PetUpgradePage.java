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
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class PetUpgradePage extends InteractiveCustomUIPage<PetUpgradePage.PetUpgradeEventData> {
    private static final int XP_BAR_WIDTH = 420;

    private final BetterPetsService service;
    private String selectedPetId;
    private boolean showResetConfirm;

    PetUpgradePage(PlayerRef playerRef, BetterPetsService service, String initialPetId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PetUpgradeEventData.CODEC);
        this.service = service;
        this.selectedPetId = normalizePetId(initialPetId);
        this.showResetConfirm = false;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append("Pages/PetUpgrade/PetUpgradePage.ui");
        render(ref, store, cmd, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PetUpgradeEventData data) {
        if (data == null || data.action == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            close();
            return;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            close();
            return;
        }

        String action = data.action.toLowerCase(Locale.ROOT);
        switch (action) {
            case "back" -> {
                showResetConfirm = false;
                List<String> pets = service.getVisiblePets(player, playerUuid);
                player.getPageManager().openCustomPage(ref, store, new PetMenuPage(playerRef, service, pets));
                return;
            }
            case "select" -> {
                selectedPetId = normalizePetId(data.petId);
                showResetConfirm = false;
            }
            case "upgrade_mob" -> {
                showResetConfirm = false;
                tryUpgrade(playerUuid, PetSkillBranch.MOB_DROPS);
            }
            case "upgrade_money" -> {
                showResetConfirm = false;
                tryUpgrade(playerUuid, PetSkillBranch.MONEY);
            }
            case "upgrade_fishing" -> {
                showResetConfirm = false;
                tryUpgrade(playerUuid, PetSkillBranch.FISHING);
            }
            case "upgrade_farming" -> {
                showResetConfirm = false;
                tryUpgrade(playerUuid, PetSkillBranch.FARMING);
            }
            case "prestige" -> {
                showResetConfirm = false;
                tryPrestige(playerUuid);
            }
            case "reset_prompt" -> showResetConfirm = true;
            case "reset_cancel" -> showResetConfirm = false;
            case "reset_confirm" -> {
                showResetConfirm = false;
                tryReset(playerUuid);
            }
            default -> {
                return;
            }
        }

        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        render(ref, store, cmd, events);
        sendUpdate(cmd, events, false);
    }

    private void tryUpgrade(UUID playerUuid, PetSkillBranch branch) {
        String petId = selectedPetId;
        if (playerUuid == null || petId == null || branch == null) {
            return;
        }
        boolean ok = service.upgradePetBranch(playerUuid, petId, branch);
        if (!ok) {
            playerRef.sendMessage(Message.raw("No unspent points for " + petId + "."));
            return;
        }
        PetProgressSnapshot progress = service.getPetProgress(playerUuid, petId);
        playerRef.sendMessage(Message.raw(
            "[Pet] " + petId + " upgraded " + branch.displayName() + " to +" + formatPercent(resolveBranchBonus(progress, branch)) + "%"
        ));
    }

    private void tryPrestige(UUID playerUuid) {
        String petId = selectedPetId;
        if (playerUuid == null || petId == null) {
            return;
        }
        boolean ok = service.prestigePet(playerUuid, petId);
        if (!ok) {
            playerRef.sendMessage(Message.raw("Prestige unavailable. Reach level 50 first."));
            return;
        }
        PetProgressSnapshot progress = service.getPetProgress(playerUuid, petId);
        playerRef.sendMessage(Message.raw(
            "[Pet] " + petId + " prestiged to P" + progress.prestige
                + ". Point values now +" + formatPercent(progress.pointIncrementPercent) + "% (Mob/Fishing/Farming)"
                + ", +" + formatPercent(progress.moneyPointIncrementPercent) + "% (Money)"
        ));
    }

    private void render(Ref<EntityStore> ref, Store<EntityStore> store, UICommandBuilder cmd, UIEventBuilder events) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            close();
            return;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null) {
            close();
            return;
        }

        List<String> visiblePets = service.getVisiblePets(player, playerUuid);
        if (visiblePets == null || visiblePets.isEmpty()) {
            playerRef.sendMessage(Message.raw("No pets available."));
            close();
            return;
        }
        visiblePets = new ArrayList<>(visiblePets);
        visiblePets.sort(Comparator.naturalOrder());
        if (selectedPetId == null || selectedPetId.isBlank()) {
            selectedPetId = normalizePetId(service.getActivePetId(playerUuid));
        }
        ensureSelectedPet(visiblePets);
        moveSelectedPetToFront(visiblePets);
        if (selectedPetId == null) {
            close();
            return;
        }

        PetProgressSnapshot progress = service.getPetProgress(playerUuid, selectedPetId);

        cmd.set("#Title.Text", "PET UPGRADE");
        cmd.set("#Subtitle.Text", selectedPetId);

        cmd.clear("#PetList");
        for (int i = 0; i < visiblePets.size(); i++) {
            String petId = visiblePets.get(i);
            cmd.append("#PetList", "Pages/PetUpgrade/PetUpgradePetEntry.ui");
            String selector = "#PetList[" + i + "]";
            cmd.set(selector + " #PetName.Text", petId);
            boolean selected = petId.equalsIgnoreCase(selectedPetId);
            cmd.set(selector + " #SelectedOverlay.Visible", selected);
            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #SelectButton",
                EventData.of("Action", "select").append("PetId", petId),
                false
            );
        }

        cmd.set("#LevelValue.Text", "Level " + progress.level + " / 50");
        cmd.set("#PrestigeValue.Text", "Prestige " + progress.prestige);
        cmd.set("#UnspentValue.Text", String.valueOf(progress.unspentPoints));
        cmd.set(
            "#PointValue.Text",
            "+" + formatPercent(progress.pointIncrementPercent) + "%/point (Mob/Fishing/Farming), +"
                + formatPercent(progress.moneyPointIncrementPercent) + "%/point (Money)"
        );

        int xpCurrent = progress.level >= 50 ? progress.xpToNextLevel : progress.xp;
        int xpTarget = progress.level >= 50 ? Math.max(1, progress.xpToNextLevel) : Math.max(1, progress.xpToNextLevel);
        if (progress.level >= 50) {
            xpCurrent = xpTarget;
        }
        cmd.set("#XpText.Text", progress.level >= 50 ? "MAX LEVEL" : (xpCurrent + " / " + xpTarget + " XP"));
        int width = (int) Math.round((xpCurrent / (double) xpTarget) * XP_BAR_WIDTH);
        width = Math.max(0, Math.min(XP_BAR_WIDTH, width));
        cmd.setObject("#XpProgressFill.Anchor", buildAnchor(width, 10));

        cmd.set("#MobValue.Text", formatBranchLine(progress.mobBonusPercent, progress.mobPoints));
        cmd.set("#MoneyValue.Text", formatBranchLine(progress.moneyBonusPercent, progress.moneyPoints));
        cmd.set("#FishingValue.Text", formatBranchLine(progress.fishingBonusPercent, progress.fishingPoints));
        cmd.set("#FarmingValue.Text", formatBranchLine(progress.farmingBonusPercent, progress.farmingPoints));

        boolean canSpend = progress.unspentPoints > 0;
        cmd.set("#MobUpgradeButton.Disabled", !canSpend);
        cmd.set("#MoneyUpgradeButton.Disabled", !canSpend);
        cmd.set("#FishingUpgradeButton.Disabled", !canSpend);
        cmd.set("#FarmingUpgradeButton.Disabled", !canSpend);

        boolean canPrestige = progress.level >= 50;
        cmd.set("#PrestigeButton.Visible", canPrestige);
        cmd.set("#PrestigeHint.Visible", !canPrestige);
        cmd.set("#PrestigeHint.Text", "Prestige available at Level 50");
        cmd.set("#ResetPetButton.Disabled", false);
        cmd.set("#ResetConfirmOverlay.Visible", showResetConfirm);
        cmd.set("#ResetConfirmPet.Text", selectedPetId);
        cmd.set("#ResetConfirmLoss.Text", buildResetLossSummary(progress));

        events.addEventBinding(CustomUIEventBindingType.Activating, "#MobUpgradeButton", EventData.of("Action", "upgrade_mob"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#MoneyUpgradeButton", EventData.of("Action", "upgrade_money"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FishingUpgradeButton", EventData.of("Action", "upgrade_fishing"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FarmingUpgradeButton", EventData.of("Action", "upgrade_farming"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PrestigeButton", EventData.of("Action", "prestige"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ResetPetButton", EventData.of("Action", "reset_prompt"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ResetConfirmCancelButton", EventData.of("Action", "reset_cancel"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ResetConfirmButton", EventData.of("Action", "reset_confirm"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", EventData.of("Action", "back"), false);
    }

    private void tryReset(UUID playerUuid) {
        String petId = selectedPetId;
        if (playerUuid == null || petId == null) {
            return;
        }
        PetProgressSnapshot before = service.getPetProgress(playerUuid, petId);
        boolean ok = service.resetPetProgress(playerUuid, petId);
        if (!ok) {
            playerRef.sendMessage(Message.raw("[Pet] Unable to reset " + petId + "."));
            return;
        }
        playerRef.sendMessage(Message.raw(
            "[Pet] " + petId + " has been reset. Lost Level " + before.level
                + ", Prestige " + before.prestige
                + ", XP " + before.xp + "/" + Math.max(1, before.xpToNextLevel)
                + ", points (Mobs " + before.mobPoints
                + ", Money " + before.moneyPoints
                + ", Fishing " + before.fishingPoints
                + ", Farming " + before.farmingPoints + ")."
        ));
    }

    private String formatBranchLine(double bonus, int points) {
        return "+" + formatPercent(bonus) + "%  (" + points + " pts)";
    }

    private String buildResetLossSummary(PetProgressSnapshot progress) {
        if (progress == null) {
            return "This reset will wipe level, prestige, XP and all branch points.";
        }
        int xpTarget = Math.max(1, progress.xpToNextLevel);
        return "You will lose: Level " + progress.level
            + ", Prestige " + progress.prestige
            + ", XP " + progress.xp + "/" + xpTarget
            + ", Unspent " + progress.unspentPoints
            + ", Mob pts " + progress.mobPoints
            + ", Money pts " + progress.moneyPoints
            + ", Fishing pts " + progress.fishingPoints
            + ", Farming pts " + progress.farmingPoints
            + ". This cannot be undone.";
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

    private Anchor buildAnchor(int width, int height) {
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(height));
        return anchor;
    }

    private void ensureSelectedPet(List<String> ownedPets) {
        if (ownedPets == null || ownedPets.isEmpty()) {
            selectedPetId = null;
            return;
        }
        if (selectedPetId == null || selectedPetId.isBlank()) {
            selectedPetId = normalizePetId(ownedPets.get(0));
            return;
        }
        for (String pet : ownedPets) {
            if (pet != null && pet.equalsIgnoreCase(selectedPetId)) {
                selectedPetId = normalizePetId(pet);
                return;
            }
        }
        selectedPetId = normalizePetId(ownedPets.get(0));
    }

    private void moveSelectedPetToFront(List<String> pets) {
        if (pets == null || pets.size() <= 1 || selectedPetId == null || selectedPetId.isBlank()) {
            return;
        }
        for (int i = 0; i < pets.size(); i++) {
            String pet = pets.get(i);
            if (pet != null && pet.equalsIgnoreCase(selectedPetId)) {
                if (i > 0) {
                    pets.remove(i);
                    pets.add(0, pet);
                }
                return;
            }
        }
    }

    private String normalizePetId(String petId) {
        if (petId == null) {
            return null;
        }
        String normalized = petId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    static final class PetUpgradeEventData {
        public static final BuilderCodec<PetUpgradeEventData> CODEC = BuilderCodec.builder(
            PetUpgradeEventData.class,
            PetUpgradeEventData::new
        )
            .append(new KeyedCodec<>("Action", Codec.STRING),
                (d, v) -> d.action = v,
                d -> d.action).add()
            .append(new KeyedCodec<>("PetId", Codec.STRING),
                (d, v) -> d.petId = v,
                d -> d.petId).add()
            .build();

        String action;
        String petId;
    }
}
