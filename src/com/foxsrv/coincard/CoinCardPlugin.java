package com.foxsrv.coincard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.concurrent.locks.ReentrantLock;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * CoinCardPlugin - Main plugin class
 * Fully asynchronous version with aggressive caching and rate limiting
 * UPDATED for Folia compatibility.
 * Now supports Vault as primary economy (Main: true) with full transaction safety.
 * Integrated with CoinCardGenerator for automatic card creation.
 * 
 * When Main: true, uses MainEconomy (synchronous, locks, busy state) to prevent double-spend.
 * When Main: false, uses CoinEconomy (asynchronous, queue-based) or an external Vault economy.
 */
public class CoinCardPlugin extends JavaPlugin {
    private static CoinCardPlugin instance;
    private static CoinCardAPI api;

    // Core components
    private Economy economy; // the economy we use internally (ours or external)
    private ConfigManager config;
    private UserStore users;
    private ApiClient apiClient;
    private AsyncQueueProcessor queueProcessor;
    private BalanceCacheManager balanceCache;
    private CoinPlaceholderExpansion placeholderExpansion;
    private BaltopUpdater baltopUpdater;
    private HistoryStore historyStore;

    // Generator instance
    private CoinCardGenerator cardGenerator;

    // Command references
    private CoinCommand coinCommand;
    private PayCommand payCommand;
    private BalanceCommand balanceCommand;
    private BalTopCommand balTopCommand;
    private HistoryCommand historyCommand;

    // Thread pool for async tasks
    private ExecutorService asyncExecutor;

    // Folia scheduled tasks
    private ScheduledTask userStoreSaveTask;
    private ScheduledTask balanceCacheSaveTask;
    private ScheduledTask historyStoreSaveTask;
    private ScheduledTask placeholderUpdateTask;

    // Encryption
    private static final String ENCRYPTION_SALT = "CoinCardSalt2024!";
    private static final String ENCRYPTION_PASSWORD = "CoinCardSecretKey2024";
    private static final int ENCRYPTION_ITERATIONS = 65536;
    private static final int ENCRYPTION_KEY_LENGTH = 256;
    private SecretKey encryptionKey;
    private byte[] encryptionIv;

    // Cooldown & Lock management
    private final Map<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> playerLocks = new ConcurrentHashMap<>();

    // ========== VAULT TRANSACTION QUEUE SYSTEM (for async mode) ==========
    // Withdraw queue (per player) - cache is zeroed immediately
    final Map<UUID, Queue<VaultWithdrawTransaction>> pendingWithdraws = new ConcurrentHashMap<>();
    final Map<UUID, Integer> withdrawAttempts = new ConcurrentHashMap<>();
    private static final int MAX_RETRIES = 10;

    // Deposit queue (global) - cache is NOT zeroed
    final Queue<VaultDepositTransaction> pendingDeposits = new ConcurrentLinkedQueue<>();
    final Set<String> withdrawPendingCards = ConcurrentHashMap.newKeySet();

    // Persistent pending store
    PendingTransactionStore pendingStore;

    // =======================================================

    public static CoinCardPlugin get() { return instance; }
    public static CoinCardAPI getAPI() { return api; }

    // ==================== ON LOAD (EARLY REGISTRATION) ====================
    @Override
    public void onLoad() {
        instance = this;
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            org.bukkit.configuration.file.FileConfiguration cfg = getConfig();
            if (cfg.getBoolean("Main", false)) {
                // Main: true → use MainEconomy (synchronous, safe)
                MainEconomy mainEconomy = new MainEconomy(this);
                getServer().getServicesManager().register(Economy.class, mainEconomy, this, ServicePriority.Highest);
                this.economy = mainEconomy;
                getLogger().info("MainEconomy registered during onLoad (priority Highest).");
            } else {
                // Main: false → use CoinEconomy (async, queue-based)
                CoinEconomy coinEconomy = new CoinEconomy(this);
                getServer().getServicesManager().register(Economy.class, coinEconomy, this, ServicePriority.Highest);
                this.economy = coinEconomy;
                getLogger().info("CoinEconomy registered during onLoad (priority Highest).");
            }
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        initEncryption();
        saveDefaultConfig();

        File usersFile = new File(getDataFolder(), "users.dat");
        if (!usersFile.exists()) {
            try {
                usersFile.createNewFile();
                getLogger().info("Created new users.dat file.");
            } catch (IOException e) {
                getLogger().warning("Could not create users.dat: " + e.getMessage());
            }
        }

        reloadLocalConfig();
        DecimalUtil.setDisplayDecimals(config.getDecimals());

        if (!isVaultPresent()) {
            getLogger().severe("Vault plugin not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (config.isMainEconomy()) {
            // Ensure MainEconomy is registered (fallback if onLoad didn't run)
            if (this.economy == null || !(this.economy instanceof MainEconomy)) {
                MainEconomy mainEconomy = new MainEconomy(this);
                getServer().getServicesManager().register(Economy.class, mainEconomy, this, ServicePriority.Highest);
                this.economy = mainEconomy;
                getLogger().info("Registered MainEconomy with priority Highest (onEnable fallback).");
            } else {
                getLogger().info("MainEconomy already registered (from onLoad).");
            }
            forceVaultEconomy(this.economy);
        } else {
            // Use existing Vault economy if available, otherwise fallback to CoinEconomy
            Economy existing = getVaultEconomy();
            if (existing != null && !existing.getName().equals("CoinCard")) {
                // Use the existing economy (external)
                this.economy = existing;
                getLogger().info("Using existing Vault economy: " + existing.getName());
            } else {
                // Register our own CoinEconomy (async)
                if (this.economy == null || !(this.economy instanceof CoinEconomy)) {
                    CoinEconomy coinEconomy = new CoinEconomy(this);
                    getServer().getServicesManager().register(Economy.class, coinEconomy, this, ServicePriority.Highest);
                    this.economy = coinEconomy;
                    getLogger().info("Registered CoinEconomy with priority Highest (onEnable fallback).");
                } else {
                    getLogger().info("CoinEconomy already registered (from onLoad).");
                }
                forceVaultEconomy(this.economy);
            }
        }

        if (!hookPlaceholderAPI()) {
            getLogger().warning("PlaceholderAPI not found. Placeholders will not be available.");
        }

        asyncExecutor = Executors.newCachedThreadPool();

        users = new UserStore(this);
        users.loadAsync();

        balanceCache = new BalanceCacheManager(this);
        balanceCache.loadFromDiskAsync();

        historyStore = new HistoryStore(this);
        historyStore.loadAsync();

        apiClient = new ApiClient(config.getApiBase(), config.getTimeoutMs(), getLogger(), balanceCache);

        // Initialize pending store (used only by CoinEconomy, harmless for MainEconomy)
        pendingStore = new PendingTransactionStore(this);
        pendingStore.load();

        long processDelayMs = config.getQueueProcessDelayMs();
        queueProcessor = new AsyncQueueProcessor(processDelayMs);
        queueProcessor.start();

        api = new CoinCardAPIImpl(this);
        getServer().getServicesManager().register(CoinCardAPI.class, api, this, ServicePriority.Normal);

        coinCommand = new CoinCommand(this, users, apiClient, queueProcessor, economy, config, balanceCache, historyStore);
        payCommand = new PayCommand(this, users, apiClient, queueProcessor, config, balanceCache);
        balanceCommand = new BalanceCommand(this, users, apiClient, balanceCache);
        balTopCommand = new BalTopCommand(this, users, apiClient, balanceCache);
        historyCommand = new HistoryCommand(this, users, historyStore);

        Objects.requireNonNull(getCommand("coin")).setExecutor(coinCommand);
        Objects.requireNonNull(getCommand("coin")).setTabCompleter(coinCommand);
        Objects.requireNonNull(getCommand("c")).setExecutor(coinCommand);
        Objects.requireNonNull(getCommand("c")).setTabCompleter(coinCommand);

        Objects.requireNonNull(getCommand("pay")).setExecutor(payCommand);
        Objects.requireNonNull(getCommand("pay")).setTabCompleter(payCommand);

        Objects.requireNonNull(getCommand("balance")).setExecutor(balanceCommand);
        Objects.requireNonNull(getCommand("balance")).setTabCompleter(balanceCommand);
        Objects.requireNonNull(getCommand("bal")).setExecutor(balanceCommand);
        Objects.requireNonNull(getCommand("bal")).setTabCompleter(balanceCommand);

        Objects.requireNonNull(getCommand("baltop")).setExecutor(balTopCommand);
        Objects.requireNonNull(getCommand("baltop")).setTabCompleter(balTopCommand);

        Objects.requireNonNull(getCommand("history")).setExecutor(historyCommand);
        Objects.requireNonNull(getCommand("history")).setTabCompleter(historyCommand);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion = new CoinPlaceholderExpansion(this, apiClient, users, balanceCache);
            placeholderExpansion.register();
            getLogger().info("Placeholder %coin_user% registered with 30s cache.");
        }

        baltopUpdater = new BaltopUpdater(this, users, apiClient, balanceCache);
        baltopUpdater.start();

        userStoreSaveTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> users.saveIfDirty(), 6000L, 6000L);
        balanceCacheSaveTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> balanceCache.saveIfDirty(), 6000L, 6000L);
        historyStoreSaveTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> historyStore.saveIfDirty(), 6000L, 6000L);

        warmupCache();

        cardGenerator = new CoinCardGenerator(this);
        cardGenerator.init();
        getLogger().info("CoinCardGenerator integrated and initialized.");

        getLogger().info("CoinCard v" + getDescription().getVersion() + " enabled (Folia).");
        String mode = config.isMainEconomy() ? "Main (sync)" : "Standalone/Async";
        getLogger().info("Mode: " + mode + " – Economy: " + economy.getName());
        getLogger().info("Commands: /coin, /c, /pay, /balance, /bal, /baltop, /history, /cardgen");
        getLogger().info("Fully asynchronous and optimized.");
        getLogger().info("Decimals config = " + config.getDecimals() + " (API remains 8 decimals internal)");
    }

    /**
     * Forces Vault to use our registered economy (either MainEconomy or CoinEconomy).
     */
    private void forceVaultEconomy(Economy ourEconomy) {
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
            Economy current = getVaultEconomy();
            if (current == null) {
                getLogger().warning("No Vault economy registered! Registering CoinCard.");
                getServer().getServicesManager().register(Economy.class, ourEconomy, this, ServicePriority.Highest);
                return;
            }
            if (!current.getName().equals("CoinCard")) {
                getLogger().warning("Vault is using " + current.getName() + " instead of CoinCard. Attempting to replace...");
                getServer().getServicesManager().unregister(Economy.class, current);
                getServer().getServicesManager().register(Economy.class, ourEconomy, this, ServicePriority.Highest);
                Economy newCurrent = getVaultEconomy();
                if (newCurrent != null && newCurrent.getName().equals("CoinCard")) {
                    getLogger().info("Successfully switched Vault to CoinCard.");
                } else {
                    getLogger().severe("Failed to switch Vault to CoinCard. Another economy plugin is overriding it. " +
                            "Consider disabling other economy plugins or setting them to not register Vault.");
                }
            } else {
                getLogger().info("Vault is correctly using CoinCard.");
            }
        }, 20L);
    }

    private void warmupCache() {
        getAsyncExecutor().submit(() -> {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                String card = users.getCard(player.getUniqueId());
                if (card != null && !card.isEmpty()) {
                    apiClient.getCardInfo(card);
                    count++;
                }
            }
            getLogger().info("Cache warmed up for " + count + " online players.");
        });
    }

    @Override
    public void onDisable() {
        if (cardGenerator != null) cardGenerator.shutdown();

        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
            }
        }

        if (queueProcessor != null) queueProcessor.shutdown();
        if (balanceCache != null) balanceCache.shutdown();
        if (users != null) users.shutdown();
        if (historyStore != null) historyStore.saveSync();
        if (placeholderExpansion != null) placeholderExpansion.shutdown();
        if (baltopUpdater != null) baltopUpdater.shutdown();
        if (api instanceof CoinCardAPIImpl) ((CoinCardAPIImpl) api).shutdown();
        if (pendingStore != null) pendingStore.save();

        if (userStoreSaveTask != null) userStoreSaveTask.cancel();
        if (balanceCacheSaveTask != null) balanceCacheSaveTask.cancel();
        if (historyStoreSaveTask != null) historyStoreSaveTask.cancel();
        if (placeholderUpdateTask != null) placeholderUpdateTask.cancel();

        getLogger().info("CoinCard disabled.");
    }

    private void initEncryption() {
        try {
            SecureRandom random = new SecureRandom();
            encryptionIv = new byte[16];
            random.nextBytes(encryptionIv);

            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(ENCRYPTION_PASSWORD.toCharArray(),
                    ENCRYPTION_SALT.getBytes(), ENCRYPTION_ITERATIONS, ENCRYPTION_KEY_LENGTH);
            SecretKey tmp = factory.generateSecret(spec);
            encryptionKey = new SecretKeySpec(tmp.getEncoded(), "AES");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize encryption: " + e.getMessage());
        }
    }

    public byte[] encrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            IvParameterSpec ivSpec = new IvParameterSpec(encryptionIv);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, ivSpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            getLogger().severe("Encryption error: " + e.getMessage());
            return data;
        }
    }

    public byte[] decrypt(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            IvParameterSpec ivSpec = new IvParameterSpec(encryptionIv);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, ivSpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            getLogger().severe("Decryption error: " + e.getMessage());
            return data;
        }
    }

    public void reloadLocalConfig() {
        reloadConfig();
        this.config = new ConfigManager(getConfig());
        DecimalUtil.setDisplayDecimals(config.getDecimals());
    }

    public void applyRuntimeConfig() {
        if (users == null) users = new UserStore(this);
        users.loadAsync();

        this.apiClient = new ApiClient(config.getApiBase(), config.getTimeoutMs(), getLogger(), balanceCache);

        if (queueProcessor != null) queueProcessor.setDelayMs(config.getQueueProcessDelayMs());

        if (coinCommand != null) {
            coinCommand.setConfig(config);
            coinCommand.setApi(apiClient);
            coinCommand.setQueue(queueProcessor);
        }

        if (payCommand != null) {
            payCommand.setConfig(config);
            payCommand.setApi(apiClient);
            payCommand.setQueue(queueProcessor);
        }

        if (balanceCommand != null) {
            balanceCommand.setApi(apiClient);
            balanceCommand.setUsers(users);
        }

        if (balTopCommand != null) {
            balTopCommand.setApi(apiClient);
            balTopCommand.setUsers(users);
        }

        if (placeholderExpansion != null) {
            placeholderExpansion.setApi(apiClient);
            placeholderExpansion.setUsers(users);
        }

        if (api instanceof CoinCardAPIImpl) {
            ((CoinCardAPIImpl) api).updateComponents(apiClient, users, config, queueProcessor);
        }

        if (baltopUpdater != null) {
            baltopUpdater.updateComponents(users, apiClient);
        }
        DecimalUtil.setDisplayDecimals(config.getDecimals());
    }

    private boolean isVaultPresent() {
        return getServer().getPluginManager().getPlugin("Vault") != null;
    }

    private Economy getVaultEconomy() {
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    private boolean hookPlaceholderAPI() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public Economy getEconomy() { return economy; }
    public ConfigManager getCoinConfig() { return config; }
    public ApiClient getApiClient() { return apiClient; }
    public AsyncQueueProcessor getQueueProcessor() { return queueProcessor; }
    public UserStore getUserStore() { return users; }
    public BalanceCacheManager getBalanceCache() { return balanceCache; }
    public HistoryStore getHistoryStore() { return historyStore; }
    public ExecutorService getAsyncExecutor() { return asyncExecutor; }
    public PendingTransactionStore getPendingStore() { return pendingStore; }

    public OfflinePlayer getServerVaultAccount() {
        try {
            UUID uuid = UUID.fromString(config.getServerVaultUUID());
            return Bukkit.getOfflinePlayer(uuid);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== COOLDOWN & LOCK MANAGEMENT ====================

    ReentrantLock getPlayerLock(UUID uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock());
    }

    boolean isOnCooldown(UUID uuid) {
        long last = cooldownMap.getOrDefault(uuid, 0L);
        long now = System.currentTimeMillis();
        return (now - last) < config.getPerUserCooldownMs();
    }

    void updateCooldown(UUID uuid) {
        cooldownMap.put(uuid, System.currentTimeMillis());
    }

    // ==================== VAULT TRANSACTION QUEUE SYSTEM (for async) ====================

    static class VaultWithdrawTransaction {
        final UUID playerUUID;
        final String card;
        final String serverCard;
        final double internalAmount;
        final double displayAmount;
        final double originalBalanceInternal;
        final long enqueuedAt;
        final String txId;

        VaultWithdrawTransaction(UUID playerUUID, String card, String serverCard,
                                 double internalAmount, double displayAmount, double originalBalanceInternal) {
            this.playerUUID = playerUUID;
            this.card = card;
            this.serverCard = serverCard;
            this.internalAmount = internalAmount;
            this.displayAmount = displayAmount;
            this.originalBalanceInternal = originalBalanceInternal;
            this.enqueuedAt = System.currentTimeMillis();
            this.txId = UUID.randomUUID().toString();
        }
    }

    static class VaultDepositTransaction {
        final UUID playerUUID;
        final String card;
        final String serverCard;
        final double internalAmount;
        final double displayAmount;
        int retryCount;
        final long enqueuedAt;
        final String txId;

        VaultDepositTransaction(UUID playerUUID, String card, String serverCard,
                                double internalAmount, double displayAmount) {
            this.playerUUID = playerUUID;
            this.card = card;
            this.serverCard = serverCard;
            this.internalAmount = internalAmount;
            this.displayAmount = displayAmount;
            this.retryCount = 0;
            this.enqueuedAt = System.currentTimeMillis();
            this.txId = UUID.randomUUID().toString();
        }
    }

    // Helper para notificar o MainEconomy (se estiver ativo)
private void notifyMainEconomy(String txId, CoinCardPlugin.ApiClient.CardTransferResult result) {
    if (economy instanceof MainEconomy) {
        ((MainEconomy) economy).completeFuture(txId, result);
    }
}

void processWithdrawQueue(UUID uuid) {
    queueProcessor.enqueue(() -> {
        ReentrantLock lock = getPlayerLock(uuid);
        if (!lock.tryLock()) {
            queueProcessor.enqueue(() -> processWithdrawQueue(uuid));
            return;
        }
        try {
            Queue<VaultWithdrawTransaction> queue = pendingWithdraws.get(uuid);
            if (queue == null || queue.isEmpty()) return;

            VaultWithdrawTransaction tx = queue.peek();
            if (tx == null) return;

            // Tenta executar a transferência
            ApiClient.CardTransferResult result = apiClient.transferByCard(tx.card, tx.serverCard, tx.internalAmount);

            if (result.success) {
                // Sucesso: remove da fila
                queue.poll();
                withdrawPendingCards.remove(tx.card);
                // Sincroniza saldo com a API
                ApiClient.CardInfoResult info = apiClient.getCardInfo(tx.card);
                if (info.success && info.coins != null) {
                    balanceCache.setBalance(tx.card, info.coins);
                }
                historyStore.addEntry(tx.playerUUID, "withdraw", tx.internalAmount,
                        "Vault withdraw (queue)", tx.originalBalanceInternal - tx.internalAmount);
                if (pendingStore != null) pendingStore.remove(tx.txId);
                notifyMainEconomy(tx.txId, result);
                processWithdrawQueue(uuid);
                return;
            }

            // Falha: verifica se é por saldo insuficiente
            String errorMsg = result.raw != null ? result.raw : "";
            boolean insufficient = errorMsg.contains("INSUFFICIENT_FUNDS") || errorMsg.contains("insufficient");

            if (insufficient) {
                // Tenta transferir TODO o saldo restante do cartão para o servidor
                ApiClient.CardInfoResult info = apiClient.getCardInfo(tx.card);
                if (info.success && info.coins != null && info.coins > 0) {
                    double remaining = info.coins;
                    ApiClient.CardTransferResult forcedResult = apiClient.transferByCard(tx.card, tx.serverCard, remaining);
                    if (forcedResult.success) {
                        balanceCache.setBalance(tx.card, 0.0);
                        historyStore.addEntry(tx.playerUUID, "forced_withdraw", remaining,
                                "Forced transfer (remaining balance)", 0.0);
                        getLogger().info("Forced transfer of " + remaining + " for " + tx.playerUUID +
                                " due to insufficient funds for original amount " + tx.displayAmount);
                    } else {
                        getLogger().warning("Failed to force transfer remaining " + remaining +
                                " for " + tx.playerUUID + ": " + forcedResult.raw);
                        // Se falhar, mantém na fila e tenta novamente
                        queueProcessor.enqueue(() -> processWithdrawQueue(uuid));
                        return;
                    }
                } else {
                    getLogger().warning("No remaining balance for " + tx.playerUUID +
                            " after insufficient funds. Removing transaction.");
                }
                // Remove a transação original (já foi tratada)
                queue.poll();
                withdrawPendingCards.remove(tx.card);
                if (pendingStore != null) pendingStore.remove(tx.txId);
                processWithdrawQueue(uuid);
                return;
            }

            // Outros erros: mantém na fila e tenta novamente (retry infinito)
            getLogger().warning("Withdraw failed for " + tx.playerUUID + " (" + tx.displayAmount +
                    "): " + errorMsg + " - retrying...");
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            queueProcessor.enqueue(() -> processWithdrawQueue(uuid));

        } finally {
            lock.unlock();
        }
    });
}

    private void restoreBalanceAfterFailedWithdraw(UUID uuid, VaultWithdrawTransaction tx) {
        ApiClient.CardInfoResult result = apiClient.getCardInfo(tx.card);
        if (result.success && result.coins != null) {
            balanceCache.setBalance(tx.card, result.coins);
        } else {
            balanceCache.setBalance(tx.card, tx.originalBalanceInternal);
        }
        getLogger().warning("Withdraw failed after " + MAX_RETRIES + " attempts for " + uuid +
                " (" + tx.displayAmount + "). Balance restored.");
    }

void processDepositQueue() {
    queueProcessor.enqueue(() -> {
        VaultDepositTransaction tx = pendingDeposits.poll();
        if (tx == null) return;

        if (withdrawPendingCards.contains(tx.card)) {
            pendingDeposits.offer(tx);
            queueProcessor.enqueue(() -> processDepositQueue());
            return;
        }

        ApiClient.CardTransferResult result = apiClient.transferByCard(tx.serverCard, tx.card, tx.internalAmount);

        if (result.success) {
            // O saldo já foi somado no MainEconomy, NÃO modifique o cache aqui.
            // Apenas registra histórico e notifica.
            historyStore.addEntry(tx.playerUUID, "deposit", tx.internalAmount,
                    "Vault deposit (queue)", balanceCache.getBalanceFast(tx.card));
            if (pendingStore != null) pendingStore.remove(tx.txId);
            notifyMainEconomy(tx.txId, result);
        } else {
            tx.retryCount++;
            if (tx.retryCount < MAX_RETRIES) {
                pendingDeposits.offer(tx);
                if (pendingStore != null) pendingStore.updateAttempts(tx.txId, tx.retryCount);
                getLogger().warning("Deposit failed (attempt " + tx.retryCount + ") for " + tx.playerUUID +
                        " (" + tx.displayAmount + "). Re-queueing.");
                processDepositQueue();
            } else {
                getLogger().severe("Deposit failed after " + MAX_RETRIES + " attempts for " + tx.playerUUID +
                        " (" + tx.displayAmount + "). Transaction lost. Manual intervention required.");
                // Rollback: subtrai o valor do cache
                Double currentInternal = balanceCache.getLastBalance(tx.card);
                if (currentInternal == null) currentInternal = 0.0;
                double newInternal = Math.max(0.0, currentInternal - tx.internalAmount);
                balanceCache.setBalance(tx.card, newInternal);
                if (pendingStore != null) pendingStore.remove(tx.txId);
                notifyMainEconomy(tx.txId, result);
            }
        }
    });
}

    // ==================== PENDING TRANSACTION STORE ====================

    public static class PendingTransactionStore {
        private final CoinCardPlugin plugin;
        private final File file;
        private YamlConfiguration config;
        private final Set<String> pendingIds = ConcurrentHashMap.newKeySet();

        public PendingTransactionStore(CoinCardPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "pending.yml");
        }

        public void load() {
            if (!file.exists()) {
                config = new YamlConfiguration();
                plugin.getLogger().info("No pending.yml found, starting fresh.");
                return;
            }
            config = YamlConfiguration.loadConfiguration(file);
            int loaded = 0;
            for (String key : config.getKeys(false)) {
                try {
                    String type = config.getString(key + ".type");
                    if ("withdraw".equals(type)) {
                        String uuidStr = config.getString(key + ".uuid");
                        String card = config.getString(key + ".card");
                        String serverCard = config.getString(key + ".serverCard");
                        double internalAmount = config.getDouble(key + ".internalAmount");
                        double displayAmount = config.getDouble(key + ".displayAmount");
                        double originalBalance = config.getDouble(key + ".originalBalance");
                        int attempts = config.getInt(key + ".attempts", 0);
                        if (uuidStr == null || card == null || serverCard == null) continue;
                        UUID uuid = UUID.fromString(uuidStr);
                        VaultWithdrawTransaction tx = new VaultWithdrawTransaction(
                                uuid, card, serverCard, internalAmount, displayAmount, originalBalance);
                        // Re-add to queue
                        plugin.pendingWithdraws.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>()).offer(tx);
                        plugin.withdrawPendingCards.add(card);
                        plugin.withdrawAttempts.put(uuid, attempts);
                        pendingIds.add(key);
                        loaded++;
                    } else if ("deposit".equals(type)) {
                        String uuidStr = config.getString(key + ".uuid");
                        String card = config.getString(key + ".card");
                        String serverCard = config.getString(key + ".serverCard");
                        double internalAmount = config.getDouble(key + ".internalAmount");
                        double displayAmount = config.getDouble(key + ".displayAmount");
                        int retryCount = config.getInt(key + ".retryCount", 0);
                        if (uuidStr == null || card == null || serverCard == null) continue;
                        UUID uuid = UUID.fromString(uuidStr);
                        VaultDepositTransaction tx = new VaultDepositTransaction(
                                uuid, card, serverCard, internalAmount, displayAmount);
                        tx.retryCount = retryCount;
                        plugin.pendingDeposits.offer(tx);
                        pendingIds.add(key);
                        loaded++;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load pending tx: " + e.getMessage());
                }
            }
            plugin.getLogger().info("Loaded " + loaded + " pending transactions from pending.yml.");
            // Re-process queues
            for (UUID uuid : plugin.pendingWithdraws.keySet()) {
                plugin.processWithdrawQueue(uuid);
            }
            if (!plugin.pendingDeposits.isEmpty()) {
                plugin.processDepositQueue();
            }
        }

        public void save() {
            if (config == null) config = new YamlConfiguration();
            // Clear old entries not in pendingIds
            for (String key : config.getKeys(false)) {
                if (!pendingIds.contains(key)) {
                    config.set(key, null);
                }
            }
            // Write current pending (withdraws)
            for (Map.Entry<UUID, Queue<VaultWithdrawTransaction>> entry : plugin.pendingWithdraws.entrySet()) {
                for (VaultWithdrawTransaction tx : entry.getValue()) {
                    String key = tx.txId;
                    config.set(key + ".type", "withdraw");
                    config.set(key + ".uuid", tx.playerUUID.toString());
                    config.set(key + ".card", tx.card);
                    config.set(key + ".serverCard", tx.serverCard);
                    config.set(key + ".internalAmount", tx.internalAmount);
                    config.set(key + ".displayAmount", tx.displayAmount);
                    config.set(key + ".originalBalance", tx.originalBalanceInternal);
                    config.set(key + ".attempts", plugin.withdrawAttempts.getOrDefault(tx.playerUUID, 0));
                    pendingIds.add(key);
                }
            }
            // Write current pending (deposits)
            for (VaultDepositTransaction tx : plugin.pendingDeposits) {
                String key = tx.txId;
                config.set(key + ".type", "deposit");
                config.set(key + ".uuid", tx.playerUUID.toString());
                config.set(key + ".card", tx.card);
                config.set(key + ".serverCard", tx.serverCard);
                config.set(key + ".internalAmount", tx.internalAmount);
                config.set(key + ".displayAmount", tx.displayAmount);
                config.set(key + ".retryCount", tx.retryCount);
                pendingIds.add(key);
            }
            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save pending.yml: " + e.getMessage());
            }
        }

        public void remove(String txId) {
            pendingIds.remove(txId);
            if (config != null) {
                config.set(txId, null);
            }
            save();
        }

        public void updateAttempts(String txId, int attempts) {
            if (config != null) {
                config.set(txId + ".attempts", attempts);
            }
            save();
        }
    }

    // ==================== PUBLIC API INTERFACE ====================

    public interface CoinCardAPI {
        String getPlayerCard(UUID uuid);
        String getPlayerCardByNick(String nick);
        boolean setPlayerCard(UUID uuid, String card);
        String getPlayerNick(UUID uuid);
        UUID getPlayerUUIDByNick(String nick);
        void transfer(String fromCard, String toCard, double amount, TransferCallback callback);
        ApiClient.CardTransferResult transferSync(String fromCard, String toCard, double amount);
        void getBalance(String card, BalanceCallback callback);
        void getPlayerBalance(UUID uuid, BalanceCallback callback);
        void getPlayerBalanceByNick(String nick, BalanceCallback callback);
        boolean hasCard(UUID uuid);
        boolean hasCardByNick(String nick);
        String getServerCard();
        String getServerVaultUUID();
        void addBalanceListener(String card, BalanceListener listener);
        void removeBalanceListener(String card, BalanceListener listener);
    }

    public interface TransferCallback {
        void onSuccess(String txId, double amount);
        void onFailure(String error);
    }

    public interface BalanceCallback {
        void onResult(double balance, String error);
    }

    public interface BalanceListener {
        void onBalanceChange(String card, double oldBalance, double newBalance);
    }

    // ==================== API IMPLEMENTATION ====================

    public static class CoinCardAPIImpl implements CoinCardAPI {
        private final CoinCardPlugin plugin;
        private ApiClient api;
        private UserStore users;
        private ConfigManager config;
        private AsyncQueueProcessor queue;
        private final Map<String, List<BalanceListener>> balanceListeners = new ConcurrentHashMap<>();
        private final Map<String, Double> lastKnownBalance = new ConcurrentHashMap<>();

        public CoinCardAPIImpl(CoinCardPlugin plugin) {
            this.plugin = plugin;
            this.api = plugin.apiClient;
            this.users = plugin.users;
            this.config = plugin.config;
            this.queue = plugin.queueProcessor;
        }

        public void updateComponents(ApiClient api, UserStore users, ConfigManager config, AsyncQueueProcessor queue) {
            this.api = api;
            this.users = users;
            this.config = config;
            this.queue = queue;
        }

        @Override
        public String getPlayerCard(UUID uuid) {
            return users.getCard(uuid);
        }

        @Override
        public String getPlayerCardByNick(String nick) {
            UUID uuid = users.findUUIDByNick(nick);
            if (uuid == null) return null;
            return users.getCard(uuid);
        }

        @Override
        public boolean setPlayerCard(UUID uuid, String card) {
            if (uuid == null || card == null || card.isEmpty()) return false;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                users.setNick(uuid, player.getName());
            }
            users.setCard(uuid, card);
            return true;
        }

        @Override
        public String getPlayerNick(UUID uuid) {
            return users.getNick(uuid);
        }

        @Override
        public UUID getPlayerUUIDByNick(String nick) {
            return users.findUUIDByNick(nick);
        }

        @Override
        public void transfer(String fromCard, String toCard, double amount, TransferCallback callback) {
            if (fromCard == null || toCard == null || amount <= 0) {
                callback.onFailure("Invalid parameters");
                return;
            }

            final double fAmount = DecimalUtil.truncate(amount, 8);

            plugin.getAsyncExecutor().submit(() -> {
                ApiClient.CardTransferResult result = api.transferByCard(fromCard, toCard, fAmount);

                if (result.success) {
                    Double oldFrom = lastKnownBalance.get(fromCard);
                    Double oldTo = lastKnownBalance.get(toCard);
                    if (oldFrom != null) notifyBalanceChange(fromCard, oldFrom, oldFrom - fAmount);
                    if (oldTo != null) notifyBalanceChange(toCard, oldTo, oldTo + fAmount);

                    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                        callback.onSuccess(result.txId, fAmount);
                    });
                } else {
                    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                        callback.onFailure("Transfer failed: " + (result.raw != null ? result.raw : "Unknown error"));
                    });
                }
            });
        }

        @Override
        public ApiClient.CardTransferResult transferSync(String fromCard, String toCard, double amount) {
            if (fromCard == null || toCard == null || amount <= 0) {
                return new ApiClient.CardTransferResult(false, null, null);
            }
            final double fAmount = DecimalUtil.truncate(amount, 8);
            return api.transferByCard(fromCard, toCard, fAmount);
        }

        @Override
        public void getBalance(String card, BalanceCallback callback) {
            if (card == null || card.isEmpty()) {
                callback.onResult(0, "Invalid card");
                return;
            }

            Double cached = plugin.balanceCache.getBalance(card);
            if (cached != null) {
                callback.onResult(cached, null);
                return;
            }

            plugin.getAsyncExecutor().submit(() -> {
                ApiClient.CardInfoResult result = api.getCardInfo(card);

                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    if (result.success && result.coins != null) {
                        double oldBalance = lastKnownBalance.getOrDefault(card, 0.0);
                        lastKnownBalance.put(card, result.coins);
                        plugin.balanceCache.setBalance(card, result.coins);

                        if (oldBalance != result.coins) {
                            notifyBalanceChange(card, oldBalance, result.coins);
                        }

                        callback.onResult(result.coins, null);
                    } else {
                        plugin.balanceCache.removeBalance(card);
                        lastKnownBalance.put(card, 0.0);
                        callback.onResult(0, result.error != null ? result.error : "Unknown error");
                    }
                });
            });
        }

        @Override
        public void getPlayerBalance(UUID uuid, BalanceCallback callback) {
            String card = getPlayerCard(uuid);
            if (card == null) {
                callback.onResult(0, "Player has no card");
                return;
            }
            getBalance(card, callback);
        }

        @Override
        public void getPlayerBalanceByNick(String nick, BalanceCallback callback) {
            String card = getPlayerCardByNick(nick);
            if (card == null) {
                callback.onResult(0, "Player has no card or not found");
                return;
            }
            getBalance(card, callback);
        }

        @Override
        public boolean hasCard(UUID uuid) {
            return getPlayerCard(uuid) != null;
        }

        @Override
        public boolean hasCardByNick(String nick) {
            return getPlayerCardByNick(nick) != null;
        }

        @Override
        public String getServerCard() {
            return config.getServerCard();
        }

        @Override
        public String getServerVaultUUID() {
            return config.getServerVaultUUID();
        }

        @Override
        public void addBalanceListener(String card, BalanceListener listener) {
            balanceListeners.computeIfAbsent(card, k -> Collections.synchronizedList(new ArrayList<>())).add(listener);
        }

        @Override
        public void removeBalanceListener(String card, BalanceListener listener) {
            List<BalanceListener> listeners = balanceListeners.get(card);
            if (listeners != null) {
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    balanceListeners.remove(card);
                }
            }
        }

        private void notifyBalanceChange(String card, double oldBalance, double newBalance) {
            List<BalanceListener> listeners = balanceListeners.get(card);
            if (listeners != null) {
                List<BalanceListener> copy = new ArrayList<>(listeners);
                for (BalanceListener listener : copy) {
                    try {
                        listener.onBalanceChange(card, oldBalance, newBalance);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Balance listener error for card " + card + ": " + e.getMessage());
                    }
                }
            }
        }

        public void shutdown() {
            balanceListeners.clear();
            lastKnownBalance.clear();
        }
    }

    // ==================== BALANCE CACHE MANAGER ====================

    public static class BalanceCacheManager {
        private final CoinCardPlugin plugin;
        private final Map<String, CachedBalance> cache = new ConcurrentHashMap<>();
        private final File cacheFile;
        private final long CACHE_DURATION_MS = 30000;
        private final Object saveLock = new Object();
        private volatile boolean dirty = false;

        private static class CachedBalance implements Serializable {
            private static final long serialVersionUID = 1L;
            final double balance;
            final long timestamp;

            CachedBalance(double balance, long timestamp) {
                this.balance = balance;
                this.timestamp = timestamp;
            }

            boolean isValid(long currentTime) {
                return (currentTime - timestamp) < 30000;
            }
        }

        public BalanceCacheManager(CoinCardPlugin plugin) {
            this.plugin = plugin;
            this.cacheFile = new File(plugin.getDataFolder(), "balance_cache.dat");
        }

        public Double getBalance(String card) {
            CachedBalance cached = cache.get(card);
            if (cached != null && cached.isValid(System.currentTimeMillis())) {
                return cached.balance;
            }
            return null;
        }

        public Double getBalanceFast(String card) {
            CachedBalance cached = cache.get(card);
            return cached != null ? cached.balance : null;
        }

        public Double getLastBalance(String card) {
            CachedBalance cached = cache.get(card);
            return cached != null ? cached.balance : null;
        }

        public double getBalanceOrDefault(String card, double defaultValue) {
            Double balance = getBalance(card);
            return balance != null ? balance : defaultValue;
        }

        public void setBalance(String card, double balance) {
            cache.put(card, new CachedBalance(DecimalUtil.truncate(balance, 8), System.currentTimeMillis()));
            markDirty();
        }

        public void removeBalance(String card) {
            cache.remove(card);
            markDirty();
        }

        private void markDirty() {
            dirty = true;
        }

        public void saveIfDirty() {
            if (dirty) saveToDiskAsync();
        }

        @SuppressWarnings("unchecked")
        public void loadFromDiskAsync() {
            plugin.getAsyncExecutor().submit(() -> {
                if (!cacheFile.exists()) {
                    plugin.getLogger().info("Balance cache file does not exist, starting fresh.");
                    return;
                }

                try (FileInputStream fis = new FileInputStream(cacheFile);
                     ObjectInputStream ois = new ObjectInputStream(fis)) {

                    Map<String, CachedBalance> loaded = (Map<String, CachedBalance>) ois.readObject();
                    long now = System.currentTimeMillis();
                    int validCount = 0;

                    for (Map.Entry<String, CachedBalance> entry : loaded.entrySet()) {
                        cache.put(entry.getKey(), entry.getValue());
                        if (entry.getValue().isValid(now)) validCount++;
                    }

                    plugin.getLogger().info("Loaded " + cache.size() + " balances from disk (" + validCount + " fresh).");

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load balance cache: " + e.getMessage());
                }
            });
        }

        public void saveToDiskAsync() {
            plugin.getAsyncExecutor().submit(() -> {
                if (!dirty) return;

                try {
                    if (!plugin.getDataFolder().exists()) {
                        plugin.getDataFolder().mkdirs();
                    }

                    File tempFile = new File(plugin.getDataFolder(), "balance_cache.dat.tmp");

                    try (FileOutputStream fos = new FileOutputStream(tempFile);
                         ObjectOutputStream oos = new ObjectOutputStream(fos)) {

                        oos.writeObject(new HashMap<>(cache));
                        oos.flush();
                    }

                    synchronized (saveLock) {
                        if (cacheFile.exists() && !cacheFile.delete()) {
                            plugin.getLogger().warning("Could not delete old balance cache file.");
                        }
                        if (tempFile.renameTo(cacheFile)) {
                            dirty = false;
                            plugin.getLogger().info("Saved " + cache.size() + " balances to disk cache.");
                        }
                    }

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save balance cache: " + e.getMessage());
                }
            });
        }

        public Map<String, Double> getAllFreshBalances() {
            Map<String, Double> result = new HashMap<>();
            long now = System.currentTimeMillis();
            for (Map.Entry<String, CachedBalance> entry : cache.entrySet()) {
                if (entry.getValue().isValid(now)) {
                    result.put(entry.getKey(), entry.getValue().balance);
                }
            }
            return result;
        }

        public Map<String, Double> getAllLastBalances() {
            Map<String, Double> result = new HashMap<>();
            for (Map.Entry<String, CachedBalance> entry : cache.entrySet()) {
                result.put(entry.getKey(), entry.getValue().balance);
            }
            return result;
        }

        public void shutdown() {
            saveToDiskAsync();
        }
    }

    // ==================== ASYNC QUEUE PROCESSOR ====================

    public static class AsyncQueueProcessor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread workerThread;
        private volatile long delayMs;

        public AsyncQueueProcessor(long delayMs) {
            this.delayMs = delayMs;
        }

        public void start() {
            if (running.getAndSet(true)) return;
            workerThread = new Thread(() -> {
                while (running.get()) {
                    Runnable task = tasks.poll();
                    if (task != null) {
                        try {
                            task.run();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            });
            workerThread.setDaemon(true);
            workerThread.start();
        }

        public void enqueue(Runnable r) {
            if (r != null) tasks.offer(r);
        }

        public void setDelayMs(long delayMs) {
            this.delayMs = delayMs;
        }

        public void shutdown() {
            running.set(false);
            if (workerThread != null) {
                workerThread.interrupt();
            }
            tasks.clear();
        }
    }

    // ==================== BALTOP UPDATER ====================

    public static class BaltopUpdater {
        private final CoinCardPlugin plugin;
        private UserStore users;
        private ApiClient api;
        private BalanceCacheManager cache;
        private Thread updaterThread;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean updating = new AtomicBoolean(false);
        private final long REQUEST_DELAY_MS = 100;
        private final long CYCLE_SLEEP_MS = 300000;

        public BaltopUpdater(CoinCardPlugin plugin, UserStore users, ApiClient api, BalanceCacheManager cache) {
            this.plugin = plugin;
            this.users = users;
            this.api = api;
            this.cache = cache;
        }

        public void updateComponents(UserStore users, ApiClient api) {
            this.users = users;
            this.api = api;
        }

        public void start() {
            if (running.getAndSet(true)) return;
            updaterThread = new Thread(() -> {
                while (running.get()) {
                    performFullUpdate();
                    if (!running.get()) break;
                    try {
                        Thread.sleep(CYCLE_SLEEP_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            updaterThread.setDaemon(true);
            updaterThread.start();
            plugin.getLogger().info("Baltop background updater started (cycle every " + (CYCLE_SLEEP_MS/1000) + " seconds).");
        }

        private void performFullUpdate() {
            if (!updating.compareAndSet(false, true)) return;
            plugin.getAsyncExecutor().submit(() -> {
                try {
                    Set<UUID> allUsers = users.getAllUsers();
                    if (allUsers.isEmpty()) return;

                    List<String> cardsToUpdate = new ArrayList<>();
                    for (UUID uuid : allUsers) {
                        String card = users.getCard(uuid);
                        if (card != null && !card.isEmpty()) {
                            cardsToUpdate.add(card);
                        }
                    }

                    plugin.getLogger().info("Baltop updater: fetching fresh balances for " + cardsToUpdate.size() + " cards...");

                    for (String card : cardsToUpdate) {
                        if (!running.get()) break;
                        try {
                            ApiClient.CardInfoResult result = api.getCardInfo(card);
                            if (result.success && result.coins != null) {
                                cache.setBalance(card, result.coins);
                            }
                            Thread.sleep(REQUEST_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            plugin.getLogger().warning("Error updating balance for card " + card + ": " + e.getMessage());
                        }
                    }

                    plugin.getLogger().info("Baltop updater finished fetching balances.");
                } finally {
                    updating.set(false);
                }
            });
        }

        public void shutdown() {
            running.set(false);
            if (updaterThread != null) {
                updaterThread.interrupt();
            }
        }
    }

    // ==================== CONFIG MANAGER ====================

    public static class ConfigManager {
        private final boolean mainEconomy;
        private final String serverVaultUUID;
        private final String serverCard;
        private final double buyVaultPerCoin;
        private final double sellCoinsPerVault;
        private final String apiBase;
        private final int queueIntervalTicks;
        private final long queueProcessDelayMs;
        private final int timeoutMs;
        private final int decimals;

        private final String defaultPassword;
        private final long defaultCooldown;
        private final int defaultNameSpace;
        private final int maxSuffixAttempts;
        private final long perUserCooldownMs;

        public ConfigManager(org.bukkit.configuration.file.FileConfiguration c) {
            this.mainEconomy = c.getBoolean("Main", false);
            this.serverVaultUUID = c.getString("Server", "");
            this.serverCard = c.getString("Card", "");
            this.buyVaultPerCoin = c.getDouble("Buy", 1.0D);
            this.sellCoinsPerVault = c.getDouble("Sell", 0.000001D);
            this.apiBase = c.getString("API", "https://bank.foxsrv.net/");
            this.queueIntervalTicks = c.getInt("QueueIntervalTicks", 20);
            this.queueProcessDelayMs = c.getLong("QueueProcessDelayMs", 1010L);
            this.timeoutMs = c.getInt("TimeoutMs", 60000);
            this.decimals = Math.min(8, Math.max(0, c.getInt("Decimals", 2)));

            this.defaultPassword = c.getString("DefaultPassword", "1234");
            this.defaultCooldown = c.getLong("DefaultCooldown", 1010L);
            this.defaultNameSpace = c.getInt("DefaultNameSpace", 2);
            this.maxSuffixAttempts = c.getInt("MaxSuffixAttempts", 10);
            this.perUserCooldownMs = c.getLong("PerUserCooldownMs", 1100L);
        }

        public boolean isMainEconomy() { return mainEconomy; }
        public String getServerVaultUUID() { return serverVaultUUID; }
        public String getServerCard() { return serverCard; }
        public double getBuyVaultPerCoin() { return buyVaultPerCoin; }
        public double getSellCoinsPerVault() { return sellCoinsPerVault; }
        public String getApiBase() { return apiBase; }
        public int getQueueIntervalTicks() { return queueIntervalTicks; }
        public long getQueueProcessDelayMs() { return queueProcessDelayMs; }
        public int getTimeoutMs() { return timeoutMs; }
        public int getDecimals() { return decimals; }

        public String getDefaultPassword() { return defaultPassword; }
        public long getDefaultCooldown() { return defaultCooldown; }
        public int getDefaultNameSpace() { return defaultNameSpace; }
        public int getMaxSuffixAttempts() { return maxSuffixAttempts; }
        public long getPerUserCooldownMs() { return perUserCooldownMs; }
    }

    // ==================== USER STORE ====================

    public static class UserStore {
        private final CoinCardPlugin plugin;
        private final File file;
        private final Map<UUID, UserData> data = new ConcurrentHashMap<>();
        private volatile boolean dirty = false;

        public UserStore(CoinCardPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "users.dat");
        }

        private static class UserData implements Serializable {
            private static final long serialVersionUID = 1L;
            String nick;
            String card;

            UserData(String nick, String card) {
                this.nick = nick;
                this.card = card;
            }
        }

        @SuppressWarnings("unchecked")
        public void loadAsync() {
            plugin.getAsyncExecutor().submit(() -> {
                if (!file.exists()) {
                    plugin.getLogger().info("users.dat does not exist, starting new file.");
                    return;
                }

                if (file.length() == 0) {
                    plugin.getLogger().info("users.dat is empty, starting new file.");
                    file.delete();
                    return;
                }

                try (FileInputStream fis = new FileInputStream(file);
                     ObjectInputStream ois = new ObjectInputStream(fis)) {

                    Map<UUID, UserData> loaded = (Map<UUID, UserData>) ois.readObject();
                    data.clear();
                    data.putAll(loaded);
                    plugin.getLogger().info("Loaded " + data.size() + " users from users.dat.");

                } catch (EOFException e) {
                    plugin.getLogger().warning("users.dat corrupted, creating backup.");
                    fazerBackupArquivoCorrompido();
                    data.clear();
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to load users.dat: " + e.getMessage());
                    fazerBackupArquivoCorrompido();
                    data.clear();
                }
            });
        }

        private void fazerBackupArquivoCorrompido() {
            try {
                File backup = new File(plugin.getDataFolder(), "users.dat.corrompido." + System.currentTimeMillis() + ".bak");
                if (file.renameTo(backup)) {
                    plugin.getLogger().info("Corrupted file saved as: " + backup.getName());
                } else {
                    file.delete();
                }
            } catch (Exception e) {
                file.delete();
            }
        }

        public void saveIfDirty() {
            if (dirty) saveAsync();
        }

        public void saveAsync() {
            plugin.getAsyncExecutor().submit(() -> {
                if (!dirty) return;

                try {
                    if (!plugin.getDataFolder().exists()) {
                        plugin.getDataFolder().mkdirs();
                    }

                    File tempFile = new File(plugin.getDataFolder(), "users.dat.tmp");

                    try (FileOutputStream fos = new FileOutputStream(tempFile);
                         ObjectOutputStream oos = new ObjectOutputStream(fos)) {

                        oos.writeObject(new HashMap<>(data));
                        oos.flush();
                    }

                    synchronized (this) {
                        if (file.exists() && !file.delete()) {
                            plugin.getLogger().warning("Could not delete old users.dat");
                        }
                        if (tempFile.renameTo(file)) {
                            dirty = false;
                            plugin.getLogger().info("Saved " + data.size() + " users to users.dat.");
                        }
                    }

                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to save users.dat: " + e.getMessage());
                }
            });
        }

        public void migrateFromYaml() {
            File yamlFile = new File(plugin.getDataFolder(), "users.yml");
            if (!yamlFile.exists()) return;

            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(yamlFile);

                int migrated = 0;
                if (yaml.isConfigurationSection("Users")) {
                    for (String key : yaml.getConfigurationSection("Users").getKeys(false)) {
                        try {
                            UUID uuid = UUID.fromString(key);
                            String nick = yaml.getString("Users." + key + ".nick");
                            String card = yaml.getString("Users." + key + ".Card");
                            if (nick != null && card != null && !data.containsKey(uuid)) {
                                data.put(uuid, new UserData(nick, card));
                                migrated++;
                                dirty = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (migrated > 0) {
                    plugin.getLogger().info("Migrated " + migrated + " users from users.yml to users.dat.");
                    saveAsync();
                    File backupFile = new File(plugin.getDataFolder(), "users.yml.bak");
                    yamlFile.renameTo(backupFile);
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to migrate from users.yml: " + e.getMessage());
            }
        }

        public void setNick(UUID uuid, String nick) {
            UserData ud = data.computeIfAbsent(uuid, k -> new UserData(nick, null));
            if (!nick.equals(ud.nick)) {
                ud.nick = nick;
                dirty = true;
            }
        }

        public void setCard(UUID uuid, String card) {
            UserData ud = data.computeIfAbsent(uuid, k -> new UserData(null, card));
            if (!card.equals(ud.card)) {
                ud.card = card;
                dirty = true;
            }
            saveAsync();
        }

        public String getNick(UUID uuid) {
            UserData ud = data.get(uuid);
            return ud != null ? ud.nick : null;
        }

        public String getCard(UUID uuid) {
            UserData ud = data.get(uuid);
            return ud != null ? ud.card : null;
        }

        public Set<UUID> getAllUsers() {
            return Collections.unmodifiableSet(data.keySet());
        }

        public UUID findUUIDByNick(String nick) {
            if (nick == null || nick.isEmpty()) return null;

            for (Map.Entry<UUID, UserData> entry : data.entrySet()) {
                if (nick.equalsIgnoreCase(entry.getValue().nick)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        public String getCardByNick(String nick) {
            UUID uuid = findUUIDByNick(nick);
            return uuid != null ? getCard(uuid) : null;
        }

        public int size() { return data.size(); }

        public void shutdown() {
            saveAsync();
        }
    }

    // ==================== HISTORY STORE ====================

    public static class HistoryStore {
        private final CoinCardPlugin plugin;
        private final File file;
        private final Map<UUID, List<HistoryEntry>> history = new ConcurrentHashMap<>();
        private volatile boolean dirty = false;
        private final int MAX_HISTORY_PER_USER = 100;

        public static class HistoryEntry implements Serializable {
            private static final long serialVersionUID = 1L;
            public final long timestamp;
            public final String type;
            public final double amount;
            public final String targetOrNote;
            public final double balanceAfter;

            public HistoryEntry(long timestamp, String type, double amount, String targetOrNote, double balanceAfter) {
                this.timestamp = timestamp;
                this.type = type;
                this.amount = amount;
                this.targetOrNote = targetOrNote;
                this.balanceAfter = balanceAfter;
            }

            public String format() {
                return new Date(timestamp).toString() + " - " + type + ": " +
                        DecimalUtil.formatDisplay(amount) + " (" + targetOrNote + ") - balance: " +
                        DecimalUtil.formatDisplay(balanceAfter);
            }
        }

        public HistoryStore(CoinCardPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "history.dat");
        }

        @SuppressWarnings("unchecked")
        public void loadAsync() {
            plugin.getAsyncExecutor().submit(() -> {
                if (!file.exists()) return;
                try (FileInputStream fis = new FileInputStream(file);
                     ObjectInputStream ois = new ObjectInputStream(fis)) {
                    Map<UUID, List<HistoryEntry>> loaded = (Map<UUID, List<HistoryEntry>>) ois.readObject();
                    history.clear();
                    history.putAll(loaded);
                    plugin.getLogger().info("Loaded history for " + history.size() + " players.");
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load history: " + e.getMessage());
                }
            });
        }

        public synchronized void addEntry(UUID uuid, String type, double amount, String targetOrNote, double balanceAfter) {
            List<HistoryEntry> list = history.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()));
            list.add(new HistoryEntry(System.currentTimeMillis(), type, amount, targetOrNote, balanceAfter));
            if (list.size() > MAX_HISTORY_PER_USER) {
                list.remove(0);
            }
            dirty = true;
        }

        public List<HistoryEntry> getHistory(UUID uuid) {
            List<HistoryEntry> list = history.get(uuid);
            if (list == null) return Collections.emptyList();
            synchronized (list) {
                return new ArrayList<>(list);
            }
        }

        public void saveIfDirty() {
            if (dirty) saveAsync();
        }

        public void saveAsync() {
            plugin.getAsyncExecutor().submit(() -> {
                if (!dirty) return;
                try (FileOutputStream fos = new FileOutputStream(file);
                     ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                    oos.writeObject(new HashMap<>(history));
                    dirty = false;
                    plugin.getLogger().fine("Saved history.");
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save history: " + e.getMessage());
                }
            });
        }

        public void saveSync() {
            if (!dirty) return;
            try (FileOutputStream fos = new FileOutputStream(file);
                 ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                oos.writeObject(new HashMap<>(history));
                dirty = false;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save history sync: " + e.getMessage());
            }
        }
    }

    // ==================== DECIMAL UTIL ====================

    public static class DecimalUtil {
        private static int displayDecimals = 2;

        public static void setDisplayDecimals(int decimals) {
            displayDecimals = Math.min(8, Math.max(0, decimals));
        }

        public static double truncate(double value, int scale) {
            if (scale < 0) scale = 0;
            BigDecimal bd = BigDecimal.valueOf(value);
            bd = bd.setScale(scale, RoundingMode.DOWN);
            return bd.doubleValue();
        }

        public static double toDisplay(double internalValue) {
            double factor = Math.pow(10, 8 - displayDecimals);
            return internalValue * factor;
        }

        public static double toInternal(double displayValue) {
            double factor = Math.pow(10, 8 - displayDecimals);
            return truncate(displayValue / factor, 8);
        }

        public static String formatDisplay(double internalValue) {
            double display = toDisplay(internalValue);
            BigDecimal bd = BigDecimal.valueOf(display).setScale(displayDecimals, RoundingMode.DOWN);
            String stripped = bd.stripTrailingZeros().toPlainString();
            if (stripped.equals("0")) return "0";
            return stripped;
        }

        public static String formatDisplayValue(double displayValue) {
            BigDecimal bd = BigDecimal.valueOf(displayValue).setScale(displayDecimals, RoundingMode.DOWN);
            String stripped = bd.stripTrailingZeros().toPlainString();
            if (stripped.equals("0")) return "0";
            return stripped;
        }

        public static String formatFull(double value) {
            if (value == 0) return "0";
            BigDecimal bd = BigDecimal.valueOf(value).stripTrailingZeros();
            return bd.toPlainString();
        }

        public static String formatFull(BigDecimal value) {
            if (value == null || value.compareTo(BigDecimal.ZERO) == 0) return "0";
            return value.stripTrailingZeros().toPlainString();
        }
    }

    // ==================== API CLIENT ====================

    public static class ApiClient {
        private final String baseUrl;
        private final int timeoutMs;
        private final Logger log;
        private final BalanceCacheManager cache;
        private final RateLimiter rateLimiter;

        public static class CardTransferResult {
            public final boolean success;
            public final String txId;
            public final String raw;

            public CardTransferResult(boolean success, String txId, String raw) {
                this.success = success;
                this.txId = txId;
                this.raw = raw;
            }
        }

        public static class CardInfoResult {
            public final boolean success;
            public final Double coins;
            public final String error;

            public CardInfoResult(boolean success, Double coins, String error) {
                this.success = success;
                this.coins = coins;
                this.error = error;
            }
        }

        public interface CardInfoResultCallback {
            void onResult(CardInfoResult result);
        }

        public ApiClient(String baseUrl, int timeoutMs, Logger logger, BalanceCacheManager cache) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl : (baseUrl + "/");
            this.timeoutMs = timeoutMs;
            this.log = logger;
            this.cache = cache;
            this.rateLimiter = new RateLimiter(500);
        }

        public CardTransferResult transferByCard(String fromCard, String toCard, double amount) {
            String endpoint = baseUrl + "api/card/pay";
            String body = "{\"fromCard\":\"" + esc(fromCard) + "\",\"toCard\":\"" + esc(toCard) + "\",\"amount\":" + amount + "}";
            try {
                rateLimiter.acquire();
                String resp = postJson(endpoint, body);
                boolean ok = parseBoolean(resp, "success");
                String tx = parseString(resp, "txId");
                if (tx == null) tx = parseString(resp, "tx");
                return new CardTransferResult(ok, tx, resp);
            } catch (IOException e) {
                log.warning("HTTP error: " + e.getMessage());
                return new CardTransferResult(false, null, null);
            }
        }

        public CardInfoResult getCardInfo(String cardCode) {
            Double fresh = cache.getBalance(cardCode);
            if (fresh != null) {
                return new CardInfoResult(true, fresh, null);
            }

            String endpoint = baseUrl + "api/card/info";
            String body = "{\"cardCode\":\"" + esc(cardCode) + "\"}";
            try {
                rateLimiter.acquire();
                String resp = postJson(endpoint, body);
                boolean success = parseBoolean(resp, "success");
                if (!success) return new CardInfoResult(false, null, parseString(resp, "error"));

                Double coins = parseDouble(resp, "coins");
                if (coins == null) coins = parseDouble(resp, "sats");

                if (coins != null) {
                    double truncated = DecimalUtil.truncate(coins, 8);
                    cache.setBalance(cardCode, truncated);
                    return new CardInfoResult(true, truncated, null);
                }

                return new CardInfoResult(true, null, null);
            } catch (IOException e) {
                log.warning("HTTP error getting card info: " + e.getMessage());
                Double last = cache.getLastBalance(cardCode);
                if (last != null) {
                    return new CardInfoResult(true, last, "Using cached balance (API unavailable)");
                }
                cache.removeBalance(cardCode);
                return new CardInfoResult(false, null, "HTTP_ERROR");
            }
        }

        private String postJson(String urlStr, String json) throws IOException {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(timeoutMs);
            con.setReadTimeout(timeoutMs);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            try (OutputStream os = con.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
            String resp = readAll(is);
            return resp != null ? resp : "";
        }

        private String readAll(InputStream is) throws IOException {
            if (is == null) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }

        private boolean parseBoolean(String json, String key) {
            String k = "\"" + key + "\"";
            int i = json.indexOf(k);
            if (i < 0) return false;
            int c = json.indexOf(':', i);
            if (c < 0) return false;
            String tail = json.substring(c + 1).trim();
            return tail.startsWith("true");
        }

        private String parseString(String json, String key) {
            String k = "\"" + key + "\"";
            int i = json.indexOf(k);
            if (i < 0) return null;
            int c = json.indexOf(':', i);
            if (c < 0) return null;
            int q1 = json.indexOf('"', c + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            return json.substring(q1 + 1, q2);
        }

        private Double parseDouble(String json, String key) {
            String k = "\"" + key + "\"";
            int i = json.indexOf(k);
            if (i < 0) return null;
            int c = json.indexOf(':', i);
            if (c < 0) return null;

            String tail = json.substring(c + 1).trim();
            StringBuilder num = new StringBuilder();
            for (int j = 0; j < tail.length(); j++) {
                char ch = tail.charAt(j);
                if (ch == ',' || ch == '}' || ch == ']') break;
                if (Character.isDigit(ch) || ch == '.' || ch == '-') num.append(ch);
            }
            try {
                return Double.parseDouble(num.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static class RateLimiter {
            private final long minIntervalMs;
            private final AtomicLong lastRequestTime = new AtomicLong(0);

            RateLimiter(long minIntervalMs) {
                this.minIntervalMs = minIntervalMs;
            }

            void acquire() {
                long now = System.currentTimeMillis();
                long last = lastRequestTime.get();
                long waitTime = minIntervalMs - (now - last);

                if (waitTime > 0) {
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                lastRequestTime.set(System.currentTimeMillis());
            }
        }
    }

    // ==================== PLACEHOLDER EXPANSION ====================

    public static class CoinPlaceholderExpansion extends PlaceholderExpansion {
        private final CoinCardPlugin plugin;
        private ApiClient api;
        private UserStore users;
        private BalanceCacheManager cache;
        private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastUpdate = new ConcurrentHashMap<>();
        private final long CACHE_TTL_MS = 30000;
        private ScheduledTask updateTask;

        public CoinPlaceholderExpansion(CoinCardPlugin plugin, ApiClient api, UserStore users, BalanceCacheManager cache) {
            this.plugin = plugin;
            this.api = api;
            this.users = users;
            this.cache = cache;
            startPeriodicUpdate();
        }

        private void startPeriodicUpdate() {
            updateTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateBalanceAsync(player.getUniqueId());
                }
            }, 100L, 100L);
        }

        public void setApi(ApiClient api) { this.api = api; }
        public void setUsers(UserStore users) { this.users = users; }

        @Override
        public String getIdentifier() { return "coin"; }

        @Override
        public String getAuthor() { return plugin.getDescription().getAuthors().toString(); }

        @Override
        public String getVersion() { return plugin.getDescription().getVersion(); }

        @Override
        public boolean persist() { return true; }

        @Override
        public boolean canRegister() { return true; }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null) return "";
            if (!params.equalsIgnoreCase("user")) return null;

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();

            Long last = lastUpdate.get(uuid);
            Double cached = balanceCache.get(uuid);

            if (cached != null && last != null && (now - last) < CACHE_TTL_MS) {
                return DecimalUtil.formatDisplay(cached);
            }

            Double diskCached = cache.getBalanceFast(users.getCard(uuid));
            if (diskCached != null) {
                balanceCache.put(uuid, diskCached);
                lastUpdate.put(uuid, now);
                return DecimalUtil.formatDisplay(diskCached);
            }

            return cached != null ? DecimalUtil.formatDisplay(cached) : "0";
        }

        private void updateBalanceAsync(UUID uuid) {
            plugin.getAsyncExecutor().submit(() -> {
                String card = users.getCard(uuid);
                if (card == null || card.isEmpty()) {
                    balanceCache.put(uuid, 0.0);
                    lastUpdate.put(uuid, System.currentTimeMillis());
                    return;
                }

                Double cached = cache.getBalance(card);
                if (cached != null) {
                    balanceCache.put(uuid, cached);
                    lastUpdate.put(uuid, System.currentTimeMillis());
                    return;
                }

                ApiClient.CardInfoResult result = api.getCardInfo(card);
                if (result.success && result.coins != null) {
                    double truncated = DecimalUtil.truncate(result.coins, 8);
                    balanceCache.put(uuid, truncated);
                    cache.setBalance(card, truncated);
                } else {
                    Double oldCache = cache.getLastBalance(card);
                    if (oldCache != null) {
                        balanceCache.put(uuid, oldCache);
                    } else {
                        balanceCache.put(uuid, 0.0);
                        cache.removeBalance(card);
                    }
                }
                lastUpdate.put(uuid, System.currentTimeMillis());
            });
        }

        public void shutdown() {
            if (updateTask != null) {
                updateTask.cancel();
            }
        }
    }

    // ==================== VAULT ECONOMY IMPLEMENTATION (QUEUE-BASED) ====================

    /**
     * CoinEconomy – asynchronous, queue-based Vault implementation.
     * Used when Main: false.
     */
    public static class CoinEconomy implements Economy {
        private final CoinCardPlugin plugin;

        public CoinEconomy(CoinCardPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean isEnabled() {
            return plugin.isEnabled();
        }

        @Override
        public String getName() {
            return "CoinCard";
        }

        @Override
        public boolean hasBankSupport() {
            return false;
        }

        @Override
        public int fractionalDigits() {
            ConfigManager cfg = plugin.getCoinConfig();
            return cfg != null ? cfg.getDecimals() : 0;
        }

        @Override
        public String format(double amount) {
            return DecimalUtil.formatDisplayValue(amount);
        }

        @Override
        public String currencyNamePlural() {
            return "coins";
        }

        @Override
        public String currencyNameSingular() {
            return "coin";
        }

        @Override
        public boolean hasAccount(String playerName) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return hasAccount(player);
        }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return player != null && plugin.getUserStore().getCard(player.getUniqueId()) != null;
        }

        @Override
        public boolean hasAccount(String playerName, String worldName) {
            return hasAccount(playerName);
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            return hasAccount(player);
        }

        // --- getBalance ---
        @Override
        public double getBalance(String playerName) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return getBalance(player);
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            if (player == null) return 0.0;
            String card = plugin.getUserStore().getCard(player.getUniqueId());
            if (card == null) return 0.0;

            // If withdraw pending, force 0
            if (plugin.withdrawPendingCards.contains(card)) {
                return 0.0;
            }

            Double internalBal = plugin.getBalanceCache().getBalanceFast(card);
            return DecimalUtil.toDisplay(internalBal != null ? internalBal : 0.0);
        }

        @Override
        public double getBalance(String playerName, String world) {
            return getBalance(playerName);
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            return getBalance(player);
        }

        // --- has ---
        @Override
        public boolean has(String playerName, double amount) {
            return getBalance(playerName) >= amount;
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return getBalance(player) >= amount;
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return has(playerName, amount);
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return has(player, amount);
        }

        // --- withdraw ---
        @Override
        public EconomyResponse withdrawPlayer(String playerName, double amount) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return withdrawPlayer(player, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            if (amount <= 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid amount");
            }
            if (player == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid player");
            }
            UUID uuid = player.getUniqueId();
            String card = plugin.getUserStore().getCard(uuid);
            if (card == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player has no card");
            }

            if (plugin.isOnCooldown(uuid)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Operation on cooldown");
            }

            if (plugin.withdrawPendingCards.contains(card)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Withdraw already pending");
            }

            ReentrantLock lock = plugin.getPlayerLock(uuid);
            if (!lock.tryLock()) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Another transaction in progress");
            }

            try {
                double internalAmount = DecimalUtil.toInternal(amount);
                double currentDisplay = getBalance(player);
                if (currentDisplay < amount) {
                    return new EconomyResponse(0, currentDisplay, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
                }

                String serverCard = plugin.getCoinConfig().getServerCard();
                if (serverCard == null || serverCard.isEmpty()) {
                    return new EconomyResponse(0, currentDisplay, EconomyResponse.ResponseType.FAILURE, "Server card not set");
                }

                Double currentInternal = plugin.getBalanceCache().getLastBalance(card);
                if (currentInternal == null) currentInternal = 0.0;

                plugin.getBalanceCache().setBalance(card, 0.0);
                plugin.withdrawPendingCards.add(card);

                VaultWithdrawTransaction tx = new VaultWithdrawTransaction(
                        uuid, card, serverCard, internalAmount, amount, currentInternal
                );
                plugin.pendingWithdraws.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>()).offer(tx);
                if (plugin.pendingStore != null) plugin.pendingStore.save();
                plugin.processWithdrawQueue(uuid);

                plugin.updateCooldown(uuid);

                return new EconomyResponse(amount, 0.0, EconomyResponse.ResponseType.SUCCESS, null);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            return withdrawPlayer(playerName, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            return withdrawPlayer(player, amount);
        }

        // --- deposit ---
        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return depositPlayer(player, amount);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            if (amount <= 0) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid amount");
            }
            if (player == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Invalid player");
            }
            UUID uuid = player.getUniqueId();
            String card = plugin.getUserStore().getCard(uuid);
            if (card == null) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player has no card");
            }

            ReentrantLock lock = plugin.getPlayerLock(uuid);
            if (!lock.tryLock()) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Another transaction in progress");
            }

            try {
                double internalAmount = DecimalUtil.toInternal(amount);
                String serverCard = plugin.getCoinConfig().getServerCard();
                if (serverCard == null || serverCard.isEmpty()) {
                    return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Server card not set");
                }

                // If withdraw pending, queue deposit but don't update cache now
                if (plugin.withdrawPendingCards.contains(card)) {
                    VaultDepositTransaction tx = new VaultDepositTransaction(
                            uuid, card, serverCard, internalAmount, amount
                    );
                    plugin.pendingDeposits.offer(tx);
                    if (plugin.pendingStore != null) plugin.pendingStore.save();
                    plugin.processDepositQueue();
                    plugin.updateCooldown(uuid);
                    return new EconomyResponse(amount, 0.0, EconomyResponse.ResponseType.SUCCESS, null);
                }

                Double currentInternal = plugin.getBalanceCache().getLastBalance(card);
                if (currentInternal == null) currentInternal = 0.0;
                double newInternal = currentInternal + internalAmount;
                plugin.getBalanceCache().setBalance(card, newInternal);

                VaultDepositTransaction tx = new VaultDepositTransaction(
                        uuid, card, serverCard, internalAmount, amount
                );
                plugin.pendingDeposits.offer(tx);
                if (plugin.pendingStore != null) plugin.pendingStore.save();
                plugin.processDepositQueue();

                plugin.updateCooldown(uuid);

                double newDisplay = DecimalUtil.toDisplay(newInternal);
                return new EconomyResponse(amount, newDisplay, EconomyResponse.ResponseType.SUCCESS, null);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            return depositPlayer(playerName, amount);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            return depositPlayer(player, amount);
        }

        // --- Banks (not supported) ---
        @Override
        public EconomyResponse createBank(String name, String player) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse createBank(String name, OfflinePlayer player) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse deleteBank(String name) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse bankBalance(String name) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse bankHas(String name, double amount) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse bankWithdraw(String name, double amount) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse bankDeposit(String name, double amount) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse isBankOwner(String name, String playerName) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse isBankMember(String name, String playerName) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public EconomyResponse isBankMember(String name, OfflinePlayer player) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Banks not supported");
        }

        @Override
        public List<String> getBanks() {
            return Collections.emptyList();
        }

        @Override
        public boolean createPlayerAccount(String playerName) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return createPlayerAccount(player);
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            if (player == null) return false;
            if (plugin.getUserStore().getCard(player.getUniqueId()) != null) return true;
            return false;
        }

        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            return createPlayerAccount(playerName);
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            return createPlayerAccount(player);
        }
    }

    // ==================== COMMAND CLASSES ====================

    public static class PayCommand implements CommandExecutor, TabCompleter {
        private final CoinCardPlugin plugin;
        private final UserStore users;
        private ApiClient api;
        private AsyncQueueProcessor queue;
        private ConfigManager cfg;
        private BalanceCacheManager cache;

        private static final String YELLOW = ChatColor.YELLOW.toString();
        private static final String GREEN = ChatColor.GREEN.toString();
        private static final String RED = ChatColor.RED.toString();
        private static final String AQUA = ChatColor.AQUA.toString();

        public PayCommand(CoinCardPlugin plugin, UserStore users, ApiClient api,
                         AsyncQueueProcessor queue, ConfigManager cfg, BalanceCacheManager cache) {
            this.plugin = plugin;
            this.users = users;
            this.api = api;
            this.queue = queue;
            this.cfg = cfg;
            this.cache = cache;
        }

        public void setApi(ApiClient api) { this.api = api; }
        public void setQueue(AsyncQueueProcessor queue) { this.queue = queue; }
        public void setConfig(ConfigManager cfg) { this.cfg = cfg; }

        private String getCardByNick(String nick) {
            Player onlinePlayer = Bukkit.getPlayerExact(nick);
            if (onlinePlayer != null) {
                String card = users.getCard(onlinePlayer.getUniqueId());
                if (card != null) return card;
            }

            UUID uuid = users.findUUIDByNick(nick);
            if (uuid != null) {
                return users.getCard(uuid);
            }

            return null;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(RED + "Players only.");
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(RED + "Usage: /pay <player> <amount>");
                return true;
            }

            Player p = (Player) sender;

            String fromCard = users.getCard(p.getUniqueId());
            if (fromCard == null || fromCard.isEmpty()) {
                p.sendMessage(RED + "Set your Card first with /coin card <card>.");
                return true;
            }

            String toCard = getCardByNick(args[0]);
            if (toCard == null) {
                p.sendMessage(RED + "Target player doesn't have a card set or doesn't exist.");
                return true;
            }

            double parsed;
            try {
                parsed = Double.parseDouble(args[1]);
            } catch (Exception e) {
                p.sendMessage(RED + "Invalid amount.");
                return true;
            }
            final double fDisplayAmount = Math.max(0.0, parsed);
            if (fDisplayAmount <= 0) {
                p.sendMessage(RED + "Invalid amount.");
                return true;
            }
            final double fAmount = DecimalUtil.toInternal(fDisplayAmount);

            final String fFromCard = fromCard;
            final String fToCard = toCard;
            final String fTargetName = args[0];

            queue.enqueue(() -> {
                ApiClient.CardTransferResult r = api.transferByCard(fFromCard, fToCard, fAmount);

                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    if (r.success) {
                        p.sendMessage(GREEN + "You sent " + YELLOW + DecimalUtil.formatDisplay(fAmount) +
                                GREEN + " to " + YELLOW + fTargetName + GREEN +
                                ". Transaction: " + AQUA + (r.txId != null ? r.txId : "-"));

                        Player onlineTarget = Bukkit.getPlayerExact(fTargetName);
                        if (onlineTarget != null && onlineTarget.isOnline()) {
                            onlineTarget.sendMessage(GREEN + "You received " + YELLOW + DecimalUtil.formatDisplay(fAmount) +
                                    GREEN + " coins from " + YELLOW + p.getName() +
                                    GREEN + ". Transaction: " + AQUA + (r.txId != null ? r.txId : "-"));
                        }

                        double newBal = cache.getBalanceOrDefault(fFromCard, 0) - fAmount;
                        plugin.getHistoryStore().addEntry(p.getUniqueId(), "pay", fAmount, fTargetName, newBal);

                        plugin.getLogger().info(p.getName() + " sent " + fAmount + " internal to " + fTargetName +
                                " (offline=" + (onlineTarget == null) + ") tx=" + r.txId);
                    } else {
                        p.sendMessage(RED + "Transaction failed. Invalid card or insufficient funds.");
                        cache.removeBalance(fFromCard);
                    }
                });
            });

            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    public static class BalanceCommand implements CommandExecutor, TabCompleter {
        private final CoinCardPlugin plugin;
        private UserStore users;
        private ApiClient api;
        private BalanceCacheManager cache;

        private static final String YELLOW = ChatColor.YELLOW.toString();
        private static final String GREEN = ChatColor.GREEN.toString();
        private static final String RED = ChatColor.RED.toString();
        private static final String GRAY = ChatColor.GRAY.toString();

        public BalanceCommand(CoinCardPlugin plugin, UserStore users, ApiClient api, BalanceCacheManager cache) {
            this.plugin = plugin;
            this.users = users;
            this.api = api;
            this.cache = cache;
        }

        public void setUsers(UserStore users) { this.users = users; }
        public void setApi(ApiClient api) { this.api = api; }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(RED + "Please specify a player from console: /balance <player>");
                    return true;
                }

                Player p = (Player) sender;
                checkBalance(p, p.getUniqueId(), p.getName());

            } else {
                if (!sender.hasPermission("coin.admin")) {
                    sender.sendMessage(RED + "You don't have permission to check other players!");
                    return true;
                }

                String targetName = args[0];
                Player onlineTarget = Bukkit.getPlayerExact(targetName);

                if (onlineTarget != null) {
                    checkBalance(sender, onlineTarget.getUniqueId(), onlineTarget.getName());
                } else {
                    UUID uuid = users.findUUIDByNick(targetName);
                    if (uuid == null) {
                        sender.sendMessage(RED + "Player not found or has no card set.");
                    } else {
                        checkBalance(sender, uuid, targetName);
                    }
                }
            }

            return true;
        }

        private void checkBalance(CommandSender sender, UUID uuid, String name) {
            String card = users.getCard(uuid);
            if (card == null || card.isEmpty()) {
                sender.sendMessage(RED + name + " doesn't have a card set.");
                return;
            }

            Double cached = cache.getBalanceFast(card);
            if (cached != null) {
                sender.sendMessage(GREEN + name + "'s balance: " + YELLOW + DecimalUtil.formatDisplay(cached));
                return;
            }

            sender.sendMessage(GRAY + "Fetching balance...");
            plugin.getAsyncExecutor().submit(() -> {
                ApiClient.CardInfoResult result = api.getCardInfo(card);
                final double finalBalance = (result.success && result.coins != null) ? result.coins : 0.0;
                if (result.success && result.coins != null) {
                    cache.setBalance(card, result.coins);
                } else {
                    cache.removeBalance(card);
                }
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    sender.sendMessage(GREEN + name + "'s balance: " + YELLOW + DecimalUtil.formatDisplay(finalBalance));
                });
            });
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1 && sender.hasPermission("coin.admin")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    public static class BalTopCommand implements CommandExecutor, TabCompleter {
        private final CoinCardPlugin plugin;
        private UserStore users;
        private ApiClient api;
        private BalanceCacheManager cache;
        private final Map<Integer, List<BalanceEntry>> pageCache = new ConcurrentHashMap<>();
        private BigDecimal totalServerBalance = BigDecimal.ZERO;
        private int totalPlayers = 0;
        private long lastCacheUpdate = 0;
        private final AtomicBoolean isUpdating = new AtomicBoolean(false);
        private final Object updateLock = new Object();
        private final long STALE_MS = 300000;

        private static final String YELLOW = ChatColor.YELLOW.toString();
        private static final String GOLD = ChatColor.GOLD.toString();
        private static final String GREEN = ChatColor.GREEN.toString();
        private static final String GRAY = ChatColor.GRAY.toString();
        private static final String WHITE = ChatColor.WHITE.toString();
        private static final String RED = ChatColor.RED.toString();
        private static final String AQUA = ChatColor.AQUA.toString();

        private static class BalanceEntry implements Comparable<BalanceEntry> {
            final String name;
            final BigDecimal balance;
            final String card;

            BalanceEntry(String name, BigDecimal balance, String card) {
                this.name = name;
                this.balance = balance;
                this.card = card;
            }

            @Override
            public int compareTo(BalanceEntry o) {
                return o.balance.compareTo(this.balance);
            }
        }

        public BalTopCommand(CoinCardPlugin plugin, UserStore users, ApiClient api, BalanceCacheManager cache) {
            this.plugin = plugin;
            this.users = users;
            this.api = api;
            this.cache = cache;
        }

        public void setUsers(UserStore users) { this.users = users; }
        public void setApi(ApiClient api) { this.api = api; }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException e) {
                    sender.sendMessage(RED + "Invalid page number. Use /baltop [page]");
                    return true;
                }
            }

            rebuildPageCache();
            refreshStaleBalancesAsync();
            displayBaltop(sender, page);
            return true;
        }

        private void rebuildPageCache() {
            if (isUpdating.get()) return;

            isUpdating.set(true);
            plugin.getAsyncExecutor().submit(() -> {
                try {
                    Set<UUID> allUsers = users.getAllUsers();
                    List<BalanceEntry> entries = new ArrayList<>();

                    for (UUID uuid : allUsers) {
                        String card = users.getCard(uuid);
                        String name = users.getNick(uuid);
                        if (card != null && !card.isEmpty() && name != null && !name.isEmpty()) {
                            Double balance = cache.getLastBalance(card);
                            if (balance == null) balance = 0.0;
                            entries.add(new BalanceEntry(name, BigDecimal.valueOf(balance), card));
                        }
                    }

                    Collections.sort(entries);

                    BigDecimal total = BigDecimal.ZERO;
                    for (BalanceEntry entry : entries) total = total.add(entry.balance);
                    totalServerBalance = total;
                    totalPlayers = entries.size();

                    Map<Integer, List<BalanceEntry>> newCache = new ConcurrentHashMap<>();
                    int pageNum = 1;
                    List<BalanceEntry> currentPage = new ArrayList<>();

                    for (int i = 0; i < entries.size(); i++) {
                        currentPage.add(entries.get(i));
                        if (currentPage.size() == 10 || i == entries.size() - 1) {
                            newCache.put(pageNum++, new ArrayList<>(currentPage));
                            currentPage.clear();
                        }
                    }

                    pageCache.clear();
                    pageCache.putAll(newCache);
                    lastCacheUpdate = System.currentTimeMillis();
                } finally {
                    isUpdating.set(false);
                }
            });
        }

        private void refreshStaleBalancesAsync() {
            plugin.getAsyncExecutor().submit(() -> {
                Set<UUID> allUsers = users.getAllUsers();
                for (UUID uuid : allUsers) {
                    String card = users.getCard(uuid);
                    if (card == null || card.isEmpty()) continue;

                    boolean needsRefresh = false;
                    CachedBalance cb = (CachedBalance) getCacheEntry(card);
                    if (cb == null) {
                        needsRefresh = true;
                    } else {
                        long age = System.currentTimeMillis() - cb.timestamp;
                        if (age > STALE_MS) needsRefresh = true;
                    }

                    if (needsRefresh) {
                        api.getCardInfo(card);
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                }
            });
        }

        private Object getCacheEntry(String card) {
            try {
                java.lang.reflect.Field field = BalanceCacheManager.class.getDeclaredField("cache");
                field.setAccessible(true);
                Map<String, ?> map = (Map<String, ?>) field.get(cache);
                return map.get(card);
            } catch (Exception e) {
                return null;
            }
        }

        private void displayBaltop(CommandSender sender, int page) {
            if (pageCache.isEmpty()) {
                sender.sendMessage(GRAY + "Building balance top, please wait...");
                plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> displayBaltop(sender, page), 20L);
                return;
            }

            List<BalanceEntry> entries = pageCache.get(page);
            if (entries == null) {
                sender.sendMessage(RED + "Page " + page + " does not exist. Total pages: " + pageCache.size());
                return;
            }

            sender.sendMessage(GOLD + "=== Balance Top (Page " + page + "/" + pageCache.size() + ") ===");
            sender.sendMessage(GRAY + "Total Server Balance: " + GREEN + DecimalUtil.formatDisplay(totalServerBalance.doubleValue()));
            sender.sendMessage(GRAY + "Total Players: " + WHITE + totalPlayers);
            sender.sendMessage("");

            int startRank = (page - 1) * 10 + 1;
            for (int i = 0; i < entries.size(); i++) {
                BalanceEntry entry = entries.get(i);
                int rank = startRank + i;
                String rankColor = rank == 1 ? GOLD : (rank == 2 ? GRAY : (rank == 3 ? AQUA : WHITE));

                sender.sendMessage(rankColor + "#" + rank + " " + WHITE + entry.name +
                        GRAY + " -> " + GREEN + DecimalUtil.formatDisplay(entry.balance.doubleValue()));
            }

            sender.sendMessage("");
            if (page > 1) sender.sendMessage(YELLOW + "Type /baltop " + (page - 1) + " to go to previous page.");
            if (page < pageCache.size()) sender.sendMessage(YELLOW + "Type /baltop " + (page + 1) + " to go to next page.");
            sender.sendMessage(GRAY + "Use " + YELLOW + "/baltop <page>" + GRAY + " to view other pages");

            if (sender instanceof Player) {
                String position = findPlayerPosition((Player) sender);
                if (position != null) sender.sendMessage(GRAY + "Your position: " + YELLOW + position);
            }
        }

        private String findPlayerPosition(Player player) {
            String playerCard = users.getCard(player.getUniqueId());
            if (playerCard == null) return "No card set";

            int position = 1;
            for (int page = 1; page <= pageCache.size(); page++) {
                List<BalanceEntry> entries = pageCache.get(page);
                if (entries == null) continue;
                for (BalanceEntry entry : entries) {
                    if (entry.card.equals(playerCard)) return "#" + position;
                    position++;
                }
            }
            return "Not in top " + totalPlayers;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> pages = new ArrayList<>();
                for (int i = 1; i <= pageCache.size(); i++) pages.add(String.valueOf(i));
                return pages;
            }
            return Collections.emptyList();
        }
    }

    public static class HistoryCommand implements CommandExecutor, TabCompleter {
        private final CoinCardPlugin plugin;
        private final UserStore users;
        private final HistoryStore historyStore;

        private static final String YELLOW = ChatColor.YELLOW.toString();
        private static final String GREEN = ChatColor.GREEN.toString();
        private static final String GRAY = ChatColor.GRAY.toString();
        private static final String RED = ChatColor.RED.toString();
        private static final String WHITE = ChatColor.WHITE.toString();

        public HistoryCommand(CoinCardPlugin plugin, UserStore users, HistoryStore historyStore) {
            this.plugin = plugin;
            this.users = users;
            this.historyStore = historyStore;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(RED + "Players only.");
                return true;
            }

            Player p = (Player) sender;
            UUID uuid = p.getUniqueId();

            if (users.getCard(uuid) == null) {
                p.sendMessage(RED + "You don't have a card set. Use /coin card <card>.");
                return true;
            }

            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException e) {
                    p.sendMessage(RED + "Invalid page number. Use /history [page]");
                    return true;
                }
            }

            List<HistoryStore.HistoryEntry> history = historyStore.getHistory(uuid);
            if (history.isEmpty()) {
                p.sendMessage(GRAY + "No transaction history found.");
                return true;
            }

            List<HistoryStore.HistoryEntry> reversed = new ArrayList<>(history);
            Collections.reverse(reversed);

            int perPage = 10;
            int totalPages = (int) Math.ceil(reversed.size() / (double) perPage);
            if (page > totalPages) page = totalPages;

            int start = (page - 1) * perPage;
            int end = Math.min(start + perPage, reversed.size());

            p.sendMessage(GREEN + "=== Transaction History (Page " + page + "/" + totalPages + ") ===");
            for (int i = start; i < end; i++) {
                HistoryStore.HistoryEntry entry = reversed.get(i);
                String typeColor = entry.type.equals("pay") ? YELLOW :
                        (entry.type.equals("deposit") ? GREEN : RED);
                p.sendMessage(typeColor + entry.format());
            }
            p.sendMessage(GRAY + "Use /history <page> to navigate.");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1 && sender instanceof Player) {
                List<HistoryStore.HistoryEntry> history = historyStore.getHistory(((Player) sender).getUniqueId());
                if (history.isEmpty()) return Collections.emptyList();
                int totalPages = (int) Math.ceil(history.size() / 10.0);
                List<String> pages = new ArrayList<>();
                for (int i = 1; i <= totalPages; i++) pages.add(String.valueOf(i));
                return pages;
            }
            return Collections.emptyList();
        }
    }

    public static class CoinCommand implements CommandExecutor, TabCompleter {
        private final CoinCardPlugin plugin;
        private final UserStore users;
        private Economy eco;
        private ApiClient api;
        private AsyncQueueProcessor queue;
        private ConfigManager cfg;
        private BalanceCacheManager cache;
        private HistoryStore historyStore;

        private static final String YELLOW = ChatColor.YELLOW.toString();
        private static final String GRAY = ChatColor.GRAY.toString();
        private static final String GREEN = ChatColor.GREEN.toString();
        private static final String RED = ChatColor.RED.toString();
        private static final String AQUA = ChatColor.AQUA.toString();
        private static final String WHITE = ChatColor.WHITE.toString();

        public CoinCommand(CoinCardPlugin plugin, UserStore users, ApiClient api,
                          AsyncQueueProcessor queue, Economy eco, ConfigManager cfg,
                          BalanceCacheManager cache, HistoryStore historyStore) {
            this.plugin = plugin;
            this.users = users;
            this.api = api;
            this.queue = queue;
            this.eco = eco;
            this.cfg = cfg;
            this.cache = cache;
            this.historyStore = historyStore;
        }

        public void setApi(ApiClient api) { this.api = api; }
        public void setQueue(AsyncQueueProcessor queue) { this.queue = queue; }
        public void setConfig(ConfigManager cfg) { this.cfg = cfg; }

        private String getCardByNick(String nick) {
            Player onlinePlayer = Bukkit.getPlayerExact(nick);
            if (onlinePlayer != null) {
                String card = users.getCard(onlinePlayer.getUniqueId());
                if (card != null) return card;
            }
            UUID uuid = users.findUUIDByNick(nick);
            return uuid != null ? users.getCard(uuid) : null;
        }

        @Override
        public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
            if (a.length == 0) {
                s.sendMessage(YELLOW + "/coin card [card] " + GRAY + "- View or set your Card");
                s.sendMessage(YELLOW + "/coin pay <player> <amount> " + GRAY + "- Pay using your Card");
                s.sendMessage(YELLOW + "/coin buy <coins> " + GRAY + "- Convert coins → vault");
                s.sendMessage(YELLOW + "/coin sell <vault> " + GRAY + "- Convert vault → coins");
                s.sendMessage(YELLOW + "/coin history [page] " + GRAY + "- View transaction history");
                if (s.hasPermission("coin.admin")) {
                    s.sendMessage(RED + "/coin reload " + GRAY + "- Reload configuration");
                    s.sendMessage(RED + "/coin server pay <player> <amount> " + GRAY + "- Pay using Server Card");
                }
                s.sendMessage("");
                s.sendMessage(GRAY + "Also available: " + YELLOW + "/pay, /balance, /bal, /baltop, /history");
                return true;
            }

            String sub = a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload":
                    if (!s.hasPermission("coin.admin")) { s.sendMessage(RED + "No permission."); return true; }
                    plugin.reloadLocalConfig();
                    plugin.applyRuntimeConfig();
                    s.sendMessage(GREEN + "Configuration reloaded.");
                    return true;

                case "card":
                    if (!(s instanceof Player)) { s.sendMessage(RED + "Players only."); return true; }
                    Player p = (Player) s;
                    if (a.length == 1) {
                        String card = users.getCard(p.getUniqueId());
                        if (card == null) p.sendMessage(RED + "You don't have a card set. Use " + YELLOW + "/coin card <card>");
                        else p.sendMessage(GREEN + "Your Card: " + YELLOW + card);
                    } else {
                        setCard(p, a[1]);
                    }
                    return true;

                case "pay":
                    if (!(s instanceof Player)) { s.sendMessage(RED + "Players only."); return true; }
                    if (a.length < 3) { s.sendMessage(RED + "Usage: " + YELLOW + "/coin pay <player> <amount>"); return true; }
                    payPlayer((Player) s, a[1], a[2]);
                    return true;

                case "buy":
                    if (!(s instanceof Player)) { s.sendMessage(RED + "Players only."); return true; }
                    if (a.length < 2) { s.sendMessage(RED + "Usage: " + YELLOW + "/coin buy <coins>"); return true; }
                    buyCoinsToVault((Player) s, a[1]);
                    return true;

                case "sell":
                    if (!(s instanceof Player)) { s.sendMessage(RED + "Players only."); return true; }
                    if (a.length < 2) { s.sendMessage(RED + "Usage: " + YELLOW + "/coin sell <vault>"); return true; }
                    sellVaultToCoins((Player) s, a[1]);
                    return true;

                case "server":
                    if (!s.hasPermission("coin.admin")) { s.sendMessage(RED + "No permission."); return true; }
                    if (a.length >= 2 && a[1].equalsIgnoreCase("pay")) {
                        if (a.length < 4) { s.sendMessage(RED + "Usage: " + YELLOW + "/coin server pay <player> <amount>"); return true; }
                        serverPay(s, a[2], a[3]);
                        return true;
                    }
                    s.sendMessage(RED + "Usage: " + YELLOW + "/coin server pay <player> <amount>");
                    return true;

                case "history":
                    if (a.length > 1) {
                        plugin.getCommand("history").execute(s, null, new String[]{a[1]});
                    } else {
                        plugin.getCommand("history").execute(s, null, new String[0]);
                    }
                    return true;

                case "balance":
                case "bal":
                    if (s instanceof Player) {
                        Player player = (Player) s;
                        if (a.length > 1) plugin.getCommand("balance").execute(s, null, new String[]{a[1]});
                        else plugin.getCommand("balance").execute(s, null, new String[0]);
                    } else s.sendMessage(RED + "Players only.");
                    return true;

                case "baltop":
                    if (a.length > 1) plugin.getCommand("baltop").execute(s, null, new String[]{a[1]});
                    else plugin.getCommand("baltop").execute(s, null, new String[0]);
                    return true;

                default:
                    s.sendMessage(RED + "Unknown subcommand. Use " + YELLOW + "/coin " + RED + "for help.");
                    return true;
            }
        }

        private void setCard(Player p, String card) {
            users.setNick(p.getUniqueId(), p.getName());
            users.setCard(p.getUniqueId(), card);
            p.sendMessage(GREEN + "Card set to: " + YELLOW + card);
        }

        private void payPlayer(Player p, String targetName, String amountStr) {
            String fromCard = users.getCard(p.getUniqueId());
            if (fromCard == null || fromCard.isEmpty()) {
                p.sendMessage(RED + "Set your Card first with " + YELLOW + "/coin card <card>" + RED + ".");
                return;
            }
            String toCard = getCardByNick(targetName);
            if (toCard == null) {
                p.sendMessage(RED + "Target player doesn't have a card set or doesn't exist.");
                return;
            }
            double parsed;
            try { parsed = Double.parseDouble(amountStr); } catch (Exception e) { p.sendMessage(RED + "Invalid amount."); return; }
            final double fDisplayAmount = Math.max(0.0, parsed);
            if (fDisplayAmount <= 0) { p.sendMessage(RED + "Invalid amount."); return; }
            final double fAmount = DecimalUtil.toInternal(fDisplayAmount);

            final String fFromCard = fromCard, fToCard = toCard, fTargetName = targetName;
            queue.enqueue(() -> {
                ApiClient.CardTransferResult r = api.transferByCard(fFromCard, fToCard, fAmount);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    if (r.success) {
                        p.sendMessage(GREEN + "You sent " + YELLOW + DecimalUtil.formatDisplay(fAmount) + GREEN + " to " + YELLOW + fTargetName + GREEN + ". Transaction: " + AQUA + (r.txId != null ? r.txId : "-"));
                        Player onlineTarget = Bukkit.getPlayerExact(fTargetName);
                        if (onlineTarget != null && onlineTarget.isOnline())
                            onlineTarget.sendMessage(GREEN + "You received " + YELLOW + DecimalUtil.formatDisplay(fAmount) + GREEN + " coins from " + YELLOW + p.getName() + GREEN + ". Transaction: " + AQUA + (r.txId != null ? r.txId : "-"));
                        double newBal = cache.getBalanceOrDefault(fFromCard, 0) - fAmount;
                        historyStore.addEntry(p.getUniqueId(), "pay", fAmount, fTargetName, newBal);
                        plugin.getLogger().info(p.getName() + " sent " + fAmount + " internal to " + fTargetName + " (offline=" + (onlineTarget == null) + ") tx=" + r.txId);
                    } else {
                        p.sendMessage(RED + "Transaction failed. Invalid card or insufficient funds.");
                        cache.removeBalance(fFromCard);
                    }
                });
            });
        }

        private void buyCoinsToVault(Player p, String coinsStr) {
            String fromCard = users.getCard(p.getUniqueId());
            String serverCard = cfg.getServerCard();
            if (fromCard == null || fromCard.isEmpty()) { p.sendMessage(RED + "Set your Card with " + YELLOW + "/coin card" + RED + "."); return; }
            if (serverCard == null || serverCard.isEmpty()) { p.sendMessage(RED + "Invalid configuration (Server Card)."); return; }
            double parsedDisplay;
            try { parsedDisplay = Double.parseDouble(coinsStr); } catch (Exception e) { p.sendMessage(RED + "Invalid amount."); return; }
            final double fDisplayCoins = Math.max(0.0, parsedDisplay);
            if (fDisplayCoins <= 0) { p.sendMessage(RED + "Invalid amount."); return; }
            final double fInternalCoins = DecimalUtil.toInternal(fDisplayCoins);
            final double fVaultToPay = DecimalUtil.truncate(fInternalCoins * cfg.getBuyVaultPerCoin(), 4);

            OfflinePlayer serverAcc = plugin.getServerVaultAccount();
            if (serverAcc == null) { p.sendMessage(RED + "Invalid configuration (Server UUID)."); return; }
            if (eco.getBalance(serverAcc) < fVaultToPay) { p.sendMessage(RED + "Failed: insufficient server balance."); return; }

            final String fFromCard = fromCard, fServerCard = serverCard;
            queue.enqueue(() -> {
                ApiClient.CardTransferResult r = api.transferByCard(fFromCard, fServerCard, fInternalCoins);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    if (!r.success) { p.sendMessage(RED + "Failed: Invalid card or insufficient coin balance."); return; }
                    eco.withdrawPlayer(serverAcc, fVaultToPay);
                    eco.depositPlayer(p, fVaultToPay);
                    String tx = (r.txId != null ? r.txId : "-");
                    double newBal = cache.getBalanceOrDefault(fFromCard, 0) - fInternalCoins;
                    historyStore.addEntry(p.getUniqueId(), "buy", fInternalCoins, "Vault", newBal);
                    p.sendMessage(GREEN + "Bought " + YELLOW + DecimalUtil.formatDisplay(fInternalCoins) + GREEN + " coins for " + YELLOW + DecimalUtil.formatFull(fVaultToPay) + GREEN + " vault. Transaction: " + AQUA + tx);
                    plugin.getLogger().info("BUY " + p.getName() + " internalCoins=" + fInternalCoins + " vault=" + fVaultToPay + " tx=" + tx);
                });
            });
        }

        private void sellVaultToCoins(Player p, String vaultStr) {
            String toCard = users.getCard(p.getUniqueId());
            String serverCard = cfg.getServerCard();
            if (toCard == null || toCard.isEmpty()) { p.sendMessage(RED + "Set your Card with " + YELLOW + "/coin card" + RED + "."); return; }
            if (serverCard == null || serverCard.isEmpty()) { p.sendMessage(RED + "Invalid configuration (Server Card)."); return; }
            double parsedVault;
            try { parsedVault = Double.parseDouble(vaultStr); } catch (Exception e) { p.sendMessage(RED + "Invalid amount."); return; }
            final double fVault = DecimalUtil.truncate(Math.max(0.0, parsedVault), 4);
            if (fVault <= 0) { p.sendMessage(RED + "Invalid amount."); return; }
            if (eco.getBalance(p) < fVault) { p.sendMessage(RED + "You don't have enough vault."); return; }

            final double fInternalCoins = DecimalUtil.truncate(fVault * cfg.getSellCoinsPerVault(), 8);
            final String fToCard = toCard, fServerCard = serverCard;
            queue.enqueue(() -> {
                ApiClient.CardTransferResult r = api.transferByCard(fServerCard, fToCard, fInternalCoins);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    if (!r.success) { p.sendMessage(RED + "Failed: Server card invalid or insufficient funds."); return; }
                    eco.withdrawPlayer(p, fVault);
                    OfflinePlayer serverAcc = plugin.getServerVaultAccount();
                    if (serverAcc != null) eco.depositPlayer(serverAcc, fVault);
                    String tx = (r.txId != null ? r.txId : "-");
                    double newBal = cache.getBalanceOrDefault(fToCard, 0) + fInternalCoins;
                    historyStore.addEntry(p.getUniqueId(), "sell", fInternalCoins, "Vault", newBal);
                    p.sendMessage(GREEN + "Sold " + YELLOW + DecimalUtil.formatFull(fVault) + GREEN + " vault for " + YELLOW + DecimalUtil.formatDisplay(fInternalCoins) + GREEN + " coins. Transaction: " + AQUA + tx);
                    if (p.isOnline()) p.sendMessage(GREEN + "You received " + YELLOW + DecimalUtil.formatDisplay(fInternalCoins) + GREEN + " coins from server. Transaction: " + AQUA + tx);
                    plugin.getLogger().info("SELL " + p.getName() + " vault=" + fVault + " internalCoins=" + fInternalCoins + " tx=" + tx);
                });
            });
        }

        private void serverPay(CommandSender s, String targetName, String amountStr) {
            String serverCard = cfg.getServerCard();
            if (serverCard == null || serverCard.isEmpty()) { s.sendMessage(RED + "Invalid configuration (Server Card)."); return; }
            String toCard = getCardByNick(targetName);
            if (toCard == null) { s.sendMessage(RED + "Target player doesn't have a card set or doesn't exist."); return; }
            double parsed;
            try { parsed = Double.parseDouble(amountStr); } catch (Exception e) { s.sendMessage(RED + "Invalid amount."); return; }
            final double fDisplayAmount = Math.max(0.0, parsed);
            if (fDisplayAmount <= 0) { s.sendMessage(RED + "Invalid amount."); return; }
            final double fAmount = DecimalUtil.toInternal(fDisplayAmount);

            final String fServerCard = serverCard, fToCard = toCard, fTargetName = targetName;
            queue.enqueue(() -> {
                ApiClient.CardTransferResult r = api.transferByCard(fServerCard, fToCard, fAmount);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    if (r.success) {
                        String tx = (r.txId != null ? r.txId : "-");
                        s.sendMessage(GREEN + "Server sent " + YELLOW + DecimalUtil.formatDisplay(fAmount) + GREEN + " to " + YELLOW + fTargetName + GREEN + ". Transaction: " + AQUA + tx);
                        Player onlineTarget = Bukkit.getPlayerExact(fTargetName);
                        if (onlineTarget != null && onlineTarget.isOnline())
                            onlineTarget.sendMessage(GREEN + "You received " + YELLOW + DecimalUtil.formatDisplay(fAmount) + GREEN + " coins from server. Transaction: " + AQUA + tx);
                        UUID targetUUID = users.findUUIDByNick(fTargetName);
                        if (targetUUID != null) {
                            double newBal = cache.getBalanceOrDefault(fToCard, 0) + fAmount;
                            historyStore.addEntry(targetUUID, "deposit", fAmount, "Server", newBal);
                        }
                        plugin.getLogger().info("SERVER PAY to " + fTargetName + " (offline=" + (onlineTarget == null) + ") internalAmount=" + fAmount + " tx=" + tx);
                    } else {
                        s.sendMessage(RED + "Failed: Server card invalid or insufficient funds.");
                    }
                });
            });
        }

        @Override
        public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
            List<String> out = new ArrayList<>();
            if (a.length == 1) {
                out.addAll(Arrays.asList("card", "pay", "buy", "sell", "balance", "bal", "baltop", "history"));
                if (s.hasPermission("coin.admin")) out.addAll(Arrays.asList("reload", "server"));
                return filter(out, a[0]);
            }
            if (a.length == 2) {
                if (a[0].equalsIgnoreCase("pay") || (a[0].equalsIgnoreCase("server") && a.length == 2)) {
                    out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                    return filter(out, a[1]);
                }
                if (a[0].equalsIgnoreCase("balance") || a[0].equalsIgnoreCase("bal")) {
                    out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                    return filter(out, a[1]);
                }
            }
            if (a.length == 3 && a[0].equalsIgnoreCase("server") && a[1].equalsIgnoreCase("pay") && s.hasPermission("coin.admin")) {
                out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                return filter(out, a[2]);
            }
            return Collections.emptyList();
        }

        private List<String> filter(List<String> base, String token) {
            if (token == null || token.isEmpty()) return base;
            String t = token.toLowerCase(Locale.ROOT);
            return base.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(t)).collect(Collectors.toList());
        }
    }

    // ==================== CachedBalance (usado internamente) ====================
    private static class CachedBalance {
        double balance;
        long timestamp;
    }
}
