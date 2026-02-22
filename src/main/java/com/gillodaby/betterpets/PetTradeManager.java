package com.gillodaby.betterpets;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class PetTradeManager {
    private static final int MAX_OFFER_PETS = 12;
    private static final UUID DEMO_UUID = new UUID(0L, 1L);
    private static final int REQUEST_EXPIRE_SECONDS = 30;
    private static final int REQUEST_COOLDOWN_SECONDS = 20;
    private static final int DENY_COOLDOWN_SECONDS = 45;
    private static final int COUNTDOWN_SECONDS = 10;

    private final BetterPetsService service;
    private final TheEconomyBridge economy;
    private final Map<UUID, TradeSession> sessionsByPlayer = new HashMap<>();
    private final Map<UUID, TradeRequest> pendingRequestsByTarget = new HashMap<>();
    private final Map<TradePairKey, Long> requestCooldowns = new HashMap<>();
    private final Map<TradePairKey, Long> denyCooldowns = new HashMap<>();

    PetTradeManager(BetterPetsService service, TheEconomyBridge economy) {
        this.service = service;
        this.economy = economy;
    }

    synchronized TradeSession getSession(UUID playerUuid) {
        return sessionsByPlayer.get(playerUuid);
    }

    synchronized boolean hasSession(UUID playerUuid) {
        return sessionsByPlayer.containsKey(playerUuid);
    }

    synchronized List<String> getOffer(UUID playerUuid) {
        TradeSession session = sessionsByPlayer.get(playerUuid);
        if (session == null) {
            return List.of();
        }
        return new ArrayList<>(session.getOffer(playerUuid));
    }

    synchronized long getMoneyOffer(UUID playerUuid) {
        TradeSession session = sessionsByPlayer.get(playerUuid);
        if (session == null) {
            return 0L;
        }
        return session.getMoneyOffer(playerUuid);
    }

    synchronized boolean isReady(UUID playerUuid) {
        TradeSession session = sessionsByPlayer.get(playerUuid);
        return session != null && session.isReady(playerUuid);
    }

    synchronized int getCountdownRemaining(UUID playerUuid) {
        TradeSession session = sessionsByPlayer.get(playerUuid);
        if (session == null) {
            return 0;
        }
        return session.getCountdownRemaining();
    }

    synchronized UUID getOtherPlayer(UUID playerUuid) {
        TradeSession session = sessionsByPlayer.get(playerUuid);
        if (session == null) {
            return null;
        }
        return session.getOther(playerUuid);
    }

    synchronized int getMaxOfferPets() {
        return MAX_OFFER_PETS;
    }

    void openDemo(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        synchronized (this) {
            TradeSession session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                session = new TradeSession(playerUuid, DEMO_UUID);
                sessionsByPlayer.put(playerUuid, session);
                sessionsByPlayer.put(DEMO_UUID, session);
            }
        }
        openTradePageFor(playerUuid);
    }

    void shutdown() {
        synchronized (this) {
            for (TradeSession session : sessionsByPlayer.values()) {
                session.cancelCountdown();
            }
            sessionsByPlayer.clear();
            for (TradeRequest request : pendingRequestsByTarget.values()) {
                if (request.expireTask != null) {
                    request.expireTask.cancel(false);
                }
            }
            pendingRequestsByTarget.clear();
            requestCooldowns.clear();
            denyCooldowns.clear();
        }
    }

    void handleDisconnect(UUID playerUuid) {
        cancelTrade(playerUuid, "Pet trade cancelled (player disconnected).");

        TradeRequest incoming;
        List<TradeRequest> outgoing = new ArrayList<>();
        synchronized (this) {
            incoming = pendingRequestsByTarget.remove(playerUuid);
            if (incoming != null && incoming.expireTask != null) {
                incoming.expireTask.cancel(false);
            }

            List<UUID> outgoingTargets = new ArrayList<>();
            for (Map.Entry<UUID, TradeRequest> entry : pendingRequestsByTarget.entrySet()) {
                TradeRequest request = entry.getValue();
                if (request != null && playerUuid.equals(request.fromUuid)) {
                    outgoingTargets.add(entry.getKey());
                    outgoing.add(request);
                }
            }
            for (UUID target : outgoingTargets) {
                TradeRequest request = pendingRequestsByTarget.remove(target);
                if (request != null && request.expireTask != null) {
                    request.expireTask.cancel(false);
                }
            }
        }
        if (incoming != null) {
            sendMessage(incoming.fromUuid, "Your pet trade request was cancelled (player disconnected).");
        }
        for (TradeRequest request : outgoing) {
            sendMessage(request.toUuid, "Incoming pet trade request cancelled (player disconnected).");
        }
    }

    boolean sendRequest(UUID fromUuid, UUID toUuid) {
        if (fromUuid == null || toUuid == null || fromUuid.equals(toUuid)) {
            return false;
        }
        if (Universe.get().getPlayer(toUuid) == null) {
            return false;
        }

        TradeRequest request;
        synchronized (this) {
            cleanupExpiredRequests();
            if (sessionsByPlayer.containsKey(fromUuid) || sessionsByPlayer.containsKey(toUuid)) {
                return false;
            }
            long now = System.currentTimeMillis();
            TradePairKey key = TradePairKey.of(fromUuid, toUuid);
            if (isCooldownActive(denyCooldowns, key, now)) {
                return false;
            }
            if (isCooldownActive(requestCooldowns, key, now)) {
                return false;
            }
            TradeRequest existing = pendingRequestsByTarget.get(toUuid);
            if (existing != null && !existing.isExpired()) {
                return false;
            }
            request = new TradeRequest(fromUuid, toUuid, now, now + (REQUEST_EXPIRE_SECONDS * 1000L));
            request.expireTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> expireRequest(toUuid, request.createdAt),
                REQUEST_EXPIRE_SECONDS,
                TimeUnit.SECONDS
            );
            pendingRequestsByTarget.put(toUuid, request);
            requestCooldowns.put(key, now + (REQUEST_COOLDOWN_SECONDS * 1000L));
        }

        String fromName = resolveName(fromUuid);
        String toName = resolveName(toUuid);
        sendMessage(fromUuid, "Pet trade request sent to " + toName + ".");
        sendMessage(toUuid, fromName + " sent you a pet trade request. Use /pet trade accept " + fromName + ".");
        return true;
    }

    boolean acceptRequest(UUID targetUuid, UUID fromUuid) {
        if (targetUuid == null || fromUuid == null) {
            return false;
        }

        TradeSession session;
        synchronized (this) {
            cleanupExpiredRequests();
            TradeRequest request = pendingRequestsByTarget.get(targetUuid);
            if (request == null || request.isExpired() || !request.fromUuid.equals(fromUuid)) {
                return false;
            }
            if (Universe.get().getPlayer(fromUuid) == null) {
                removeRequest(targetUuid);
                return false;
            }
            if (sessionsByPlayer.containsKey(targetUuid) || sessionsByPlayer.containsKey(fromUuid)) {
                removeRequest(targetUuid);
                return false;
            }
            removeRequest(targetUuid);
            session = new TradeSession(fromUuid, targetUuid);
            sessionsByPlayer.put(fromUuid, session);
            sessionsByPlayer.put(targetUuid, session);
        }

        String fromName = resolveName(fromUuid);
        String toName = resolveName(targetUuid);
        sendMessage(fromUuid, "Pet trade started with " + toName + ".");
        sendMessage(targetUuid, "Pet trade started with " + fromName + ".");
        refreshSession(session);
        return true;
    }

    boolean declineRequest(UUID targetUuid, UUID fromUuid) {
        if (targetUuid == null || fromUuid == null) {
            return false;
        }
        boolean removed;
        synchronized (this) {
            cleanupExpiredRequests();
            TradeRequest request = pendingRequestsByTarget.get(targetUuid);
            removed = request != null && request.fromUuid.equals(fromUuid) && !request.isExpired();
            if (removed) {
                removeRequest(targetUuid);
                long now = System.currentTimeMillis();
                denyCooldowns.put(TradePairKey.of(fromUuid, targetUuid), now + (DENY_COOLDOWN_SECONDS * 1000L));
            }
        }
        if (removed) {
            sendMessage(targetUuid, "Pet trade request declined.");
            sendMessage(fromUuid, resolveName(targetUuid) + " declined your pet trade request.");
        }
        return removed;
    }

    void cancelTrade(UUID playerUuid, String reason) {
        TradeSession session;
        synchronized (this) {
            session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                return;
            }
            removeSession(session);
        }
        session.cancelCountdown();
        sendMessage(session.playerA, reason);
        sendMessage(session.playerB, reason);
    }

    List<String> getAvailablePets(UUID playerUuid) {
        UUID otherUuid;
        Set<String> offered;
        synchronized (this) {
            TradeSession session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                return List.of();
            }
            otherUuid = session.getOther(playerUuid);
            offered = new HashSet<>(session.getOffer(playerUuid));
        }
        List<String> owned;
        if (DEMO_UUID.equals(otherUuid)) {
            owned = getVisiblePets(playerUuid);
        } else {
            owned = service.getOwnedPets(playerUuid);
        }
        if (owned.isEmpty()) {
            return List.of();
        }

        List<String> available = new ArrayList<>();
        for (String petId : owned) {
            if (petId == null || petId.isBlank()) {
                continue;
            }
            if (offered.contains(petId)) {
                continue;
            }
            if (service.ownsPet(otherUuid, petId)) {
                continue;
            }
            available.add(petId);
        }
        available.sort(Comparator.naturalOrder());
        return available;
    }

    void addOfferPet(UUID playerUuid, String petId) {
        TradeSession session;
        String normalized = normalizePetId(petId);
        if (normalized == null) {
            return;
        }
        synchronized (this) {
            session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                return;
            }
            List<String> offer = session.getOffer(playerUuid);
            if (offer.size() >= MAX_OFFER_PETS) {
                sendMessage(playerUuid, "You can only offer up to " + MAX_OFFER_PETS + " pets.");
                return;
            }
            if (!service.ownsPet(playerUuid, normalized)) {
                sendMessage(playerUuid, "You don't own that pet.");
                return;
            }
            if (offer.contains(normalized)) {
                sendMessage(playerUuid, "That pet is already in your offer.");
                return;
            }
            UUID otherUuid = session.getOther(playerUuid);
            if (service.ownsPet(otherUuid, normalized)) {
                sendMessage(playerUuid, "The other player already owns this pet.");
                return;
            }
            offer.add(normalized);
            session.resetReady();
            session.cancelCountdown();
        }
        refreshSession(session);
    }

    void removeOfferPet(UUID playerUuid, int index) {
        TradeSession session;
        synchronized (this) {
            session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                return;
            }
            List<String> offer = session.getOffer(playerUuid);
            if (index < 0 || index >= offer.size()) {
                return;
            }
            offer.remove(index);
            session.resetReady();
            session.cancelCountdown();
        }
        refreshSession(session);
    }

    void adjustMoneyOffer(UUID playerUuid, long delta) {
        if (delta == 0L) {
            return;
        }
        TradeSession session;
        long maxMoney = getMaxMoneyOffer(playerUuid);
        synchronized (this) {
            session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                return;
            }
            long current = session.getMoneyOffer(playerUuid);
            long next = clampMoney(current + delta, maxMoney);
            if (next == current) {
                return;
            }
            session.setMoneyOffer(playerUuid, next);
            session.resetReady();
            session.cancelCountdown();
        }
        refreshSession(session);
    }

    long getMaxMoneyOffer(UUID playerUuid) {
        if (!economy.isConnected()) {
            return 0L;
        }
        double balance = economy.balance(playerUuid);
        if (balance <= 0) {
            return 0L;
        }
        return (long) Math.floor(balance);
    }

    void toggleReady(UUID playerUuid) {
        TradeSession session;
        boolean ready;
        synchronized (this) {
            session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                return;
            }
            ready = !session.isReady(playerUuid);
            session.setReady(playerUuid, ready);
            if (!ready) {
                session.cancelCountdown();
            }
        }
        refreshSession(session);
        if (ready) {
            startCountdownIfReady(session);
        }
    }

    void openTradePage(UUID playerUuid) {
        synchronized (this) {
            if (!sessionsByPlayer.containsKey(playerUuid)) {
                return;
            }
        }
        openTradePageFor(playerUuid);
    }

    UUID resolveOnlineUuid(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref != null && ref.getUsername() != null && ref.getUsername().equalsIgnoreCase(name)) {
                return ref.getUuid();
            }
        }
        return null;
    }

    String resolveName(UUID uuid) {
        if (uuid == null) {
            return "Player";
        }
        if (DEMO_UUID.equals(uuid)) {
            return "Demo";
        }
        PlayerRef ref = Universe.get().getPlayer(uuid);
        if (ref != null && ref.getUsername() != null && !ref.getUsername().isBlank()) {
            return ref.getUsername();
        }
        return "Player";
    }

    private List<String> getVisiblePets(UUID playerUuid) {
        if (playerUuid == null) {
            return List.of();
        }
        PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
        if (playerRef == null) {
            return service.getOwnedPets(playerUuid);
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null) {
            return service.getOwnedPets(playerUuid);
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return service.getOwnedPets(playerUuid);
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return service.getOwnedPets(playerUuid);
        }
        List<String> visible = service.getVisiblePets(player, playerUuid);
        if (visible == null || visible.isEmpty()) {
            return service.getOwnedPets(playerUuid);
        }
        return visible;
    }

    private void startCountdownIfReady(TradeSession session) {
        synchronized (this) {
            if (!isSessionActive(session)) {
                return;
            }
            if (!session.areBothReady() || session.countdownRemaining > 0) {
                return;
            }
            session.countdownRemaining = COUNTDOWN_SECONDS;
            session.countdownTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                () -> tickCountdown(session),
                1L,
                1L,
                TimeUnit.SECONDS
            );
        }
        refreshSession(session);
    }

    private void tickCountdown(TradeSession session) {
        boolean finalize = false;
        synchronized (this) {
            if (!isSessionActive(session)) {
                session.cancelCountdown();
                return;
            }
            if (!session.areBothReady()) {
                session.cancelCountdown();
                return;
            }
            session.countdownRemaining -= 1;
            if (session.countdownRemaining <= 0) {
                session.cancelCountdown();
                finalize = true;
            }
        }
        refreshSession(session);
        if (finalize) {
            finalizeTrade(session);
        }
    }

    private void finalizeTrade(TradeSession session) {
        UUID playerA = session.playerA;
        UUID playerB = session.playerB;
        List<String> offerA;
        List<String> offerB;
        long moneyA;
        long moneyB;

        synchronized (this) {
            if (!isSessionActive(session)) {
                return;
            }
            offerA = new ArrayList<>(session.offerA);
            offerB = new ArrayList<>(session.offerB);
            moneyA = session.moneyA;
            moneyB = session.moneyB;
        }

        if ((moneyA > 0 || moneyB > 0) && !economy.isConnected()) {
            cancelTrade(playerA, "Pet trade failed (TheEconomy unavailable).");
            return;
        }
        if (moneyA > 0 && economy.balance(playerA) < moneyA) {
            cancelTrade(playerA, "Pet trade failed (insufficient funds).");
            return;
        }
        if (moneyB > 0 && economy.balance(playerB) < moneyB) {
            cancelTrade(playerB, "Pet trade failed (insufficient funds).");
            return;
        }

        if (moneyA > 0 && !economy.charge(playerA, moneyA)) {
            cancelTrade(playerA, "Pet trade failed (money transfer).");
            return;
        }
        if (moneyB > 0 && !economy.charge(playerB, moneyB)) {
            if (moneyA > 0) {
                economy.grant(playerA, moneyA);
            }
            cancelTrade(playerB, "Pet trade failed (money transfer).");
            return;
        }

        boolean petsMoved = service.tradePets(playerA, offerA, playerB, offerB);
        if (!petsMoved) {
            if (moneyA > 0) {
                economy.grant(playerA, moneyA);
            }
            if (moneyB > 0) {
                economy.grant(playerB, moneyB);
            }
            cancelTrade(playerA, "Pet trade failed (pet ownership changed).");
            return;
        }

        if (moneyA > 0 && !economy.grant(playerB, moneyA)) {
            economy.grant(playerA, moneyA);
            sendMessage(playerA, "Trade warning: money delivery to other player failed, refund issued.");
            sendMessage(playerB, "Trade warning: failed to receive money from the trade.");
        }
        if (moneyB > 0 && !economy.grant(playerA, moneyB)) {
            economy.grant(playerB, moneyB);
            sendMessage(playerB, "Trade warning: money delivery to other player failed, refund issued.");
            sendMessage(playerA, "Trade warning: failed to receive money from the trade.");
        }

        synchronized (this) {
            removeSession(session);
        }
        sendMessage(playerA, "Pet trade completed.");
        sendMessage(playerB, "Pet trade completed.");
        openPostTradePageFor(playerA);
        openPostTradePageFor(playerB);
    }

    private void refreshSession(TradeSession session) {
        openTradePageFor(session.playerA);
        openTradePageFor(session.playerB);
    }

    private void openTradePageFor(UUID playerUuid) {
        PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
        if (playerRef == null) {
            return;
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null) {
            return;
        }
        world.execute(() -> {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getPageManager() == null) {
                return;
            }
            player.getPageManager().openCustomPage(ref, store, new PetTradePage(playerRef, playerUuid, this, service, economy));
        });
    }

    private void openPostTradePageFor(UUID playerUuid) {
        PlayerRef playerRef = Universe.get().getPlayer(playerUuid);
        if (playerRef == null) {
            return;
        }
        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null) {
            return;
        }
        world.execute(() -> {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getPageManager() == null) {
                return;
            }
            List<String> visiblePets = service.getVisiblePets(player, playerUuid);
            if (visiblePets == null || visiblePets.isEmpty()) {
                visiblePets = service.getOwnedPets(playerUuid);
            }
            player.getPageManager().openCustomPage(ref, store, new PetMenuPage(playerRef, service, visiblePets));
        });
    }

    private synchronized void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        List<UUID> expiredTargets = new ArrayList<>();
        for (Map.Entry<UUID, TradeRequest> entry : pendingRequestsByTarget.entrySet()) {
            TradeRequest request = entry.getValue();
            if (request != null && request.expiresAt <= now) {
                expiredTargets.add(entry.getKey());
            }
        }
        for (UUID target : expiredTargets) {
            removeRequest(target);
        }
        requestCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        denyCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private synchronized void removeRequest(UUID targetUuid) {
        TradeRequest request = pendingRequestsByTarget.remove(targetUuid);
        if (request != null && request.expireTask != null) {
            request.expireTask.cancel(false);
        }
    }

    private void expireRequest(UUID targetUuid, long createdAt) {
        TradeRequest request;
        synchronized (this) {
            request = pendingRequestsByTarget.get(targetUuid);
            if (request == null || request.createdAt != createdAt || !request.isExpired()) {
                return;
            }
            removeRequest(targetUuid);
        }
        sendMessage(request.fromUuid, "Your pet trade request expired.");
        sendMessage(request.toUuid, "Incoming pet trade request expired.");
    }

    private boolean isSessionActive(TradeSession session) {
        synchronized (this) {
            TradeSession current = sessionsByPlayer.get(session.playerA);
            return current == session;
        }
    }

    private synchronized void removeSession(TradeSession session) {
        sessionsByPlayer.remove(session.playerA);
        sessionsByPlayer.remove(session.playerB);
    }

    private long clampMoney(long value, long max) {
        if (value < 0) {
            return 0L;
        }
        if (max < 0) {
            return 0L;
        }
        return Math.min(value, max);
    }

    private boolean isCooldownActive(Map<TradePairKey, Long> map, TradePairKey key, long now) {
        Long until = map.get(key);
        return until != null && until > now;
    }

    private String normalizePetId(String petId) {
        if (petId == null) {
            return null;
        }
        String normalized = petId.trim().toLowerCase();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private void sendMessage(UUID uuid, String message) {
        PlayerRef ref = Universe.get().getPlayer(uuid);
        if (ref != null) {
            ref.sendMessage(Message.raw(message));
        }
    }

    static final class TradeSession {
        private final UUID playerA;
        private final UUID playerB;
        private final List<String> offerA = new ArrayList<>();
        private final List<String> offerB = new ArrayList<>();
        private boolean readyA;
        private boolean readyB;
        private long moneyA;
        private long moneyB;
        private int countdownRemaining;
        private ScheduledFuture<?> countdownTask;

        TradeSession(UUID playerA, UUID playerB) {
            this.playerA = playerA;
            this.playerB = playerB;
        }

        UUID getOther(UUID playerUuid) {
            return playerA.equals(playerUuid) ? playerB : playerA;
        }

        List<String> getOffer(UUID playerUuid) {
            return playerA.equals(playerUuid) ? offerA : offerB;
        }

        boolean isReady(UUID playerUuid) {
            return playerA.equals(playerUuid) ? readyA : readyB;
        }

        void setReady(UUID playerUuid, boolean value) {
            if (playerA.equals(playerUuid)) {
                readyA = value;
            } else {
                readyB = value;
            }
        }

        boolean areBothReady() {
            return readyA && readyB;
        }

        void resetReady() {
            readyA = false;
            readyB = false;
        }

        long getMoneyOffer(UUID playerUuid) {
            return playerA.equals(playerUuid) ? moneyA : moneyB;
        }

        void setMoneyOffer(UUID playerUuid, long value) {
            if (playerA.equals(playerUuid)) {
                moneyA = value;
            } else {
                moneyB = value;
            }
        }

        int getCountdownRemaining() {
            return countdownRemaining;
        }

        void cancelCountdown() {
            if (countdownTask != null) {
                countdownTask.cancel(false);
                countdownTask = null;
            }
            countdownRemaining = 0;
        }
    }

    private static final class TradePairKey {
        private final UUID from;
        private final UUID to;

        private TradePairKey(UUID from, UUID to) {
            this.from = from;
            this.to = to;
        }

        static TradePairKey of(UUID from, UUID to) {
            return new TradePairKey(from, to);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TradePairKey key)) {
                return false;
            }
            return Objects.equals(from, key.from) && Objects.equals(to, key.to);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to);
        }
    }

    private static final class TradeRequest {
        private final UUID fromUuid;
        private final UUID toUuid;
        private final long createdAt;
        private final long expiresAt;
        private ScheduledFuture<?> expireTask;

        private TradeRequest(UUID fromUuid, UUID toUuid, long createdAt, long expiresAt) {
            this.fromUuid = fromUuid;
            this.toUuid = toUuid;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }
}
