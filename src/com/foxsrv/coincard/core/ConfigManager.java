package com.foxsrv.coincard.core;

import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {
    private final String serverVaultUUID;
    private final String serverCoinID;
    private final String serverCard;
    private final double buyVaultPerCoin;  // vault recebido por 1 coin (coins->vault)
    private final double sellCoinsPerVault; // coins recebidos por 1 vault (vault->coins)
    private final String apiBase;
    private final int queueIntervalTicks;
    private final long perUserCooldownMs;
    private final int timeoutMs;

    public ConfigManager(FileConfiguration c) {
        this.serverVaultUUID = c.getString("Server", "");
        this.serverCoinID = c.getString("ServerID", "");
        this.serverCard = c.getString("Card", "");
        this.buyVaultPerCoin = c.getDouble("Buy", 0.0D);
        this.sellCoinsPerVault = c.getDouble("Sell", 0.0D);
        this.apiBase = c.getString("API", "http://127.0.0.1:26450/");
        this.queueIntervalTicks = c.getInt("QueueIntervalTicks", 20);
        this.perUserCooldownMs = c.getLong("PerUserCooldownMs", 1000L);
        this.timeoutMs = c.getInt("TimeoutMs", 10000);
    }

    public String getServerVaultUUID() { return serverVaultUUID; }
    public String getServerCoinID() { return serverCoinID; }
    public String getServerCard() { return serverCard; }
    public double getBuyVaultPerCoin() { return buyVaultPerCoin; }
    public double getSellCoinsPerVault() { return sellCoinsPerVault; }
    public String getApiBase() { return apiBase; }
    public int getQueueIntervalTicks() { return queueIntervalTicks; }
    public long getPerUserCooldownMs() { return perUserCooldownMs; }
    public int getTimeoutMs() { return timeoutMs; }
}
