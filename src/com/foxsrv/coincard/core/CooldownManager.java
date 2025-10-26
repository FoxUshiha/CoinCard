package com.foxsrv.coincard.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {
    private final long cooldownMs;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public CooldownManager(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public boolean checkAndStamp(UUID user) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(user);
        if (last != null && (now - last) < cooldownMs) return false;
        lastUse.put(user, now);
        return true;
    }
}
