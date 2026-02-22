package com.gillodaby.betterpets;

import java.lang.reflect.Method;
import java.util.UUID;

final class TheEconomyBridge {
    private Method getInstanceMethod;
    private Method getBalanceMethod;
    private Method addBalanceMethod;
    private Method removeBalanceMethod;

    boolean connect() {
        try {
            Class<?> apiClass = Class.forName("com.economy.api.EconomyAPI");
            getInstanceMethod = apiClass.getMethod("getInstance");
            getBalanceMethod = apiClass.getMethod("getBalance", UUID.class);
            addBalanceMethod = apiClass.getMethod("addBalance", UUID.class, double.class);
            removeBalanceMethod = apiClass.getMethod("removeBalance", UUID.class, double.class);
            return true;
        } catch (Throwable ignored) {
            getInstanceMethod = null;
            getBalanceMethod = null;
            addBalanceMethod = null;
            removeBalanceMethod = null;
            return false;
        }
    }

    boolean isConnected() {
        return getInstanceMethod != null
            && getBalanceMethod != null
            && addBalanceMethod != null
            && removeBalanceMethod != null;
    }

    double balance(UUID playerUuid) {
        if (playerUuid == null) {
            return 0.0;
        }
        Object api = resolveApi();
        if (api == null) {
            return 0.0;
        }
        try {
            Object value = getBalanceMethod.invoke(api, playerUuid);
            if (value instanceof Number number) {
                return Math.max(0.0, number.doubleValue());
            }
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    boolean charge(UUID playerUuid, double amount) {
        if (playerUuid == null || amount <= 0.0) {
            return true;
        }
        Object api = resolveApi();
        if (api == null) {
            return false;
        }
        try {
            Object value = removeBalanceMethod.invoke(api, playerUuid, amount);
            return value instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean grant(UUID playerUuid, double amount) {
        if (playerUuid == null || amount <= 0.0) {
            return true;
        }
        Object api = resolveApi();
        if (api == null) {
            return false;
        }
        try {
            addBalanceMethod.invoke(api, playerUuid, amount);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    String unitSymbol() {
        return "$";
    }

    private Object resolveApi() {
        if (!isConnected()) {
            connect();
            if (!isConnected()) {
                return null;
            }
        }
        try {
            return getInstanceMethod.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
