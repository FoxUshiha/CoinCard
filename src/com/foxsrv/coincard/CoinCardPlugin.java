package com.foxsrv.coincard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

import net.milkbowl.vault.economy.Economy;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * CoinCardPlugin - Main plugin class
 * Provides card-based coin transactions with Vault integration
 */
public class CoinCardPlugin extends JavaPlugin {
    private static CoinCardPlugin instance;
    private static CoinCardAPI api;
    
    // Core components
    private Economy economy;
    private ConfigManager config;
    private UserStore users;
    private ApiClient apiClient;
    private QueueService queue;
    private CooldownManager cooldowns;
    private CoinPlaceholderExpansion placeholderExpansion;
    
    // Command reference
    private CoinCommand coinCommand;
    
    public static CoinCardPlugin get() { return instance; }
    public static CoinCardAPI getAPI() { return api; }
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Save default configs
        saveDefaultConfig();
        saveResource("users.yml", false);
        reloadLocalConfig();
        
        // Check dependencies
        if (!hookVault()) {
            getLogger().severe("Vault not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        if (!hookPlaceholderAPI()) {
            getLogger().warning("PlaceholderAPI not found. Placeholders will not be available.");
        }
        
        // Initialize components
        users = new UserStore(this);
        users.load();
        
        apiClient = new ApiClient(config.getApiBase(), config.getTimeoutMs(), getLogger());
        queue = new QueueService(this, config.getQueueIntervalTicks());
        cooldowns = new CooldownManager(config.getPerUserCooldownMs());
        
        // Initialize API
        api = new CoinCardAPIImpl(this);
        
        // Register as a service for other plugins
        getServer().getServicesManager().register(CoinCardAPI.class, api, this, ServicePriority.Normal);
        
        // Register command
        coinCommand = new CoinCommand(this, users, apiClient, queue, cooldowns, economy, config);
        Objects.requireNonNull(getCommand("coin")).setExecutor(coinCommand);
        Objects.requireNonNull(getCommand("coin")).setTabCompleter(coinCommand);
        
        // Register placeholder if PlaceholderAPI is available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderExpansion = new CoinPlaceholderExpansion(this, apiClient, users);
            placeholderExpansion.register();
            getLogger().info("Placeholder %coin_user% registered.");
        }
        
        getLogger().info("CoinCard v" + getDescription().getVersion() + " enabled.");
    }
    
    @Override
    public void onDisable() {
        if (users != null) users.save();
        if (queue != null) queue.shutdown();
        if (placeholderExpansion != null) placeholderExpansion.unregister();
        if (api instanceof CoinCardAPIImpl) {
            ((CoinCardAPIImpl) api).shutdown();
        }
        getLogger().info("CoinCard disabled.");
    }
    
    public void reloadLocalConfig() {
        reloadConfig();
        this.config = new ConfigManager(getConfig());
    }
    
    public void applyRuntimeConfig() {
        if (users == null) users = new UserStore(this);
        users.load();
        
        this.apiClient = new ApiClient(config.getApiBase(), config.getTimeoutMs(), getLogger());
        this.cooldowns = new CooldownManager(config.getPerUserCooldownMs());
        
        if (queue != null) queue.shutdown();
        queue = new QueueService(this, config.getQueueIntervalTicks());
        
        if (coinCommand != null) {
            coinCommand.setConfig(config);
            coinCommand.setApi(apiClient);
            coinCommand.setQueue(queue);
            coinCommand.setCooldowns(cooldowns);
        }
        
        if (placeholderExpansion != null) {
            placeholderExpansion.setApi(apiClient);
            placeholderExpansion.setUsers(users);
        }
        
        // Update API implementation
        if (api instanceof CoinCardAPIImpl) {
            ((CoinCardAPIImpl) api).updateComponents(apiClient, users, config, queue);
        }
    }
    
    private boolean hookVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }
    
    private boolean hookPlaceholderAPI() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }
    
    public Economy getEconomy() { return economy; }
    public ConfigManager getCoinConfig() { return config; }
    public ApiClient getApiClient() { return apiClient; }
    public QueueService getQueue() { return queue; }
    public CooldownManager getCooldowns() { return cooldowns; }
    public UserStore getUserStore() { return users; }
    
    public OfflinePlayer getServerVaultAccount() {
        try {
            UUID uuid = UUID.fromString(config.getServerVaultUUID());
            return Bukkit.getOfflinePlayer(uuid);
        } catch (Exception e) {
            return null;
        }
    }
    
    // ==================== PUBLIC API INTERFACE ====================
    
    /**
     * CoinCardAPI - Public API for other plugins to interact with CoinCard
     */
    public interface CoinCardAPI {
        /**
         * Gets a player's card by UUID
         * @param uuid Player's UUID
         * @return Card string or null if not set
         */
        String getPlayerCard(UUID uuid);
        
        /**
         * Gets a player's card by nickname (works for offline players)
         * @param nick Player's nickname
         * @return Card string or null if not found
         */
        String getPlayerCardByNick(String nick);
        
        /**
         * Sets a player's card
         * @param uuid Player's UUID
         * @param card Card to set
         * @return true if successful
         */
        boolean setPlayerCard(UUID uuid, String card);
        
        /**
         * Gets a player's nickname by UUID
         * @param uuid Player's UUID
         * @return Nickname or null
         */
        String getPlayerNick(UUID uuid);
        
        /**
         * Gets a player's UUID by nickname (works for offline players)
         * @param nick Player's nickname
         * @return UUID or null if not found
         */
        UUID getPlayerUUIDByNick(String nick);
        
        /**
         * Performs an asynchronous transfer from one card to another
         * @param fromCard Source card
         * @param toCard Destination card
         * @param amount Amount to transfer
         * @param callback Callback for result
         */
        void transfer(String fromCard, String toCard, double amount, TransferCallback callback);
        
        /**
         * Performs a synchronous transfer (blocking - use with caution!)
         * @param fromCard Source card
         * @param toCard Destination card
         * @param amount Amount to transfer
         * @return Transfer result
         */
        ApiClient.CardTransferResult transferSync(String fromCard, String toCard, double amount);
        
        /**
         * Gets a card's balance asynchronously
         * @param card Card to check
         * @param callback Callback with balance
         */
        void getBalance(String card, BalanceCallback callback);
        
        /**
         * Gets a player's balance by UUID
         * @param uuid Player's UUID
         * @param callback Callback with balance
         */
        void getPlayerBalance(UUID uuid, BalanceCallback callback);
        
        /**
         * Gets a player's balance by nickname (works for offline players)
         * @param nick Player's nickname
         * @param callback Callback with balance
         */
        void getPlayerBalanceByNick(String nick, BalanceCallback callback);
        
        /**
         * Checks if a player has a card set
         * @param uuid Player's UUID
         * @return true if player has a card
         */
        boolean hasCard(UUID uuid);
        
        /**
         * Checks if a nickname has a card set (works for offline players)
         * @param nick Player's nickname
         * @return true if player has a card
         */
        boolean hasCardByNick(String nick);
        
        /**
         * Gets the server's card
         * @return Server card string
         */
        String getServerCard();
        
        /**
         * Gets the server's vault account UUID
         * @return Server vault UUID
         */
        String getServerVaultUUID();
        
        /**
         * Adds a balance listener for a specific card
         * @param card Card to monitor
         * @param listener Listener to add
         */
        void addBalanceListener(String card, BalanceListener listener);
        
        /**
         * Removes a balance listener
         * @param card Card being monitored
         * @param listener Listener to remove
         */
        void removeBalanceListener(String card, BalanceListener listener);
    }
    
    /**
     * Callback for transfer operations
     */
    public interface TransferCallback {
        void onSuccess(String txId, double amount);
        void onFailure(String error);
    }
    
    /**
     * Callback for balance queries
     */
    public interface BalanceCallback {
        void onResult(double balance, String error);
    }
    
    /**
     * Listener for balance changes
     */
    public interface BalanceListener {
        void onBalanceChange(String card, double oldBalance, double newBalance);
    }
    
    // ==================== API IMPLEMENTATION ====================
    
    /**
     * CoinCardAPIImpl - Implementation of the public API
     */
    public static class CoinCardAPIImpl implements CoinCardAPI {
        private final CoinCardPlugin plugin;
        private ApiClient api;
        private UserStore users;
        private ConfigManager config;
        private QueueService queue;
        private final Map<String, List<BalanceListener>> balanceListeners = new ConcurrentHashMap<>();
        private final Map<String, Double> lastKnownBalance = new ConcurrentHashMap<>();
        private int monitorTaskId = -1;
        
        public CoinCardAPIImpl(CoinCardPlugin plugin) {
            this.plugin = plugin;
            this.api = plugin.apiClient;
            this.users = plugin.users;
            this.config = plugin.config;
            this.queue = plugin.queue;
            startBalanceMonitor();
        }
        
        public void updateComponents(ApiClient api, UserStore users, ConfigManager config, QueueService queue) {
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
            
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ApiClient.CardTransferResult result = api.transferByCard(fromCard, toCard, fAmount);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.success) {
                        // Update cached balances
                        updateCachedBalance(fromCard, -fAmount);
                        updateCachedBalance(toCard, fAmount);
                        callback.onSuccess(result.txId, fAmount);
                    } else {
                        callback.onFailure("Transfer failed: " + (result.raw != null ? result.raw : "Unknown error"));
                    }
                });
            });
        }
        
        @Override
        public ApiClient.CardTransferResult transferSync(String fromCard, String toCard, double amount) {
            if (fromCard == null || toCard == null || amount <= 0) {
                return new ApiClient.CardTransferResult(false, null, null);
            }
            
            final double fAmount = DecimalUtil.truncate(amount, 8);
            
            // This is a blocking call - use with caution!
            ApiClient.CardTransferResult result = api.transferByCard(fromCard, toCard, fAmount);
            
            if (result.success) {
                updateCachedBalance(fromCard, -fAmount);
                updateCachedBalance(toCard, fAmount);
            }
            
            return result;
        }
        
        @Override
        public void getBalance(String card, BalanceCallback callback) {
            if (card == null || card.isEmpty()) {
                callback.onResult(0, "Invalid card");
                return;
            }
            
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ApiClient.CardInfoResult result = api.getCardInfo(card);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.success && result.coins != null) {
                        double oldBalance = lastKnownBalance.getOrDefault(card, 0.0);
                        lastKnownBalance.put(card, result.coins);
                        
                        if (oldBalance != result.coins) {
                            notifyBalanceChange(card, oldBalance, result.coins);
                        }
                        
                        callback.onResult(result.coins, null);
                    } else {
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
        
        private void updateCachedBalance(String card, double delta) {
            double oldBalance = lastKnownBalance.getOrDefault(card, 0.0);
            double newBalance = oldBalance + delta;
            lastKnownBalance.put(card, newBalance);
            notifyBalanceChange(card, oldBalance, newBalance);
        }
        
        private void notifyBalanceChange(String card, double oldBalance, double newBalance) {
            List<BalanceListener> listeners = balanceListeners.get(card);
            if (listeners != null) {
                // Create a copy to avoid concurrent modification
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
        
        private void startBalanceMonitor() {
            monitorTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                // Periodically update balances for monitored cards
                if (!balanceListeners.isEmpty()) {
                    Set<String> cardsToMonitor = new HashSet<>(balanceListeners.keySet());
                    for (String card : cardsToMonitor) {
                        getBalance(card, new BalanceCallback() {
                            @Override
                            public void onResult(double balance, String error) {
                                // Balance already updated via getBalance
                            }
                        });
                    }
                }
            }, 200L, 200L); // Update every 10 seconds
        }
        
        public void shutdown() {
            if (monitorTaskId != -1) {
                Bukkit.getScheduler().cancelTask(monitorTaskId);
            }
            balanceListeners.clear();
            lastKnownBalance.clear();
        }
    }
    
    // ==================== INNER CLASSES ====================
    
    /**
     * ConfigManager - Manages plugin configuration
     */
    public static class ConfigManager {
        private final String serverVaultUUID;
        private final String serverCard;
        private final double buyVaultPerCoin;
        private final double sellCoinsPerVault;
        private final String apiBase;
        private final int queueIntervalTicks;
        private final long perUserCooldownMs;
        private final int timeoutMs;
    
        public ConfigManager(org.bukkit.configuration.file.FileConfiguration c) {
            this.serverVaultUUID = c.getString("Server", "");
            this.serverCard = c.getString("Card", "");
            this.buyVaultPerCoin = c.getDouble("Buy", 0.0D);
            this.sellCoinsPerVault = c.getDouble("Sell", 0.0D);
            this.apiBase = c.getString("API", "http://127.0.0.1:26450/");
            this.queueIntervalTicks = c.getInt("QueueIntervalTicks", 20);
            this.perUserCooldownMs = c.getLong("PerUserCooldownMs", 1000L);
            this.timeoutMs = c.getInt("TimeoutMs", 10000);
        }
    
        public String getServerVaultUUID() { return serverVaultUUID; }
        public String getServerCard() { return serverCard; }
        public double getBuyVaultPerCoin() { return buyVaultPerCoin; }
        public double getSellCoinsPerVault() { return sellCoinsPerVault; }
        public String getApiBase() { return apiBase; }
        public int getQueueIntervalTicks() { return queueIntervalTicks; }
        public long getPerUserCooldownMs() { return perUserCooldownMs; }
        public int getTimeoutMs() { return timeoutMs; }
    }
    
    /**
     * UserStore - Manages user data (UUID -> Card)
     */
    public static class UserStore {
        private final JavaPlugin plugin;
        private final File file;
        private final YamlConfiguration yaml = new YamlConfiguration();
    
        public UserStore(JavaPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "users.yml");
        }
    
        public void load() {
            if (!file.exists()) plugin.saveResource("users.yml", false);
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                plugin.getLogger().severe("Failed to load users.yml: " + e.getMessage());
            }
        }
    
        public void save() {
            try { yaml.save(file); } catch (IOException e) {
                plugin.getLogger().severe("Failed to save users.yml: " + e.getMessage());
            }
        }
    
        private String basePath(UUID uuid) { return "Users." + uuid.toString(); }
    
        public void setNick(UUID uuid, String nick) {
            yaml.set(basePath(uuid) + ".nick", nick);
            save();
        }
    
        public void setCard(UUID uuid, String card) {
            yaml.set(basePath(uuid) + ".Card", card);
            save();
        }
    
        public String getNick(UUID uuid) { return yaml.getString(basePath(uuid) + ".nick", null); }
        public String getCard(UUID uuid) { return yaml.getString(basePath(uuid) + ".Card", null); }
    
        public UUID findUUIDByNick(String nick) {
            if (nick == null || nick.isEmpty()) return null;
            if (!yaml.isConfigurationSection("Users")) return null;
            
            for (String key : yaml.getConfigurationSection("Users").getKeys(false)) {
                String n = yaml.getString("Users." + key + ".nick", "");
                if (nick.equalsIgnoreCase(n)) {
                    try { 
                        return UUID.fromString(key); 
                    } catch (Exception ignored) {}
                }
            }
            return null;
        }
        
        public String getCardByNick(String nick) {
            UUID u = findUUIDByNick(nick);
            if (u == null) return null;
            return getCard(u);
        }
    }
    
    /**
     * CooldownManager - Manages per-user cooldowns
     */
    public static class CooldownManager {
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
    
    /**
     * QueueService - Synchronous task queue
     */
    public static class QueueService {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private final int taskId;
    
        public QueueService(JavaPlugin plugin, int intervalTicks) {
            this.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                Runnable r = tasks.poll();
                if (r != null) {
                    try { r.run(); } catch (Throwable t) { 
                        plugin.getLogger().warning("Task error: " + t.getMessage()); 
                    }
                }
            }, intervalTicks, intervalTicks);
        }
    
        public void enqueue(Runnable r) { if (r != null) tasks.offer(r); }
        public void shutdown() { Bukkit.getScheduler().cancelTask(taskId); tasks.clear(); }
    }
    
    /**
     * DecimalUtil - Utility for truncating decimals
     */
    public static class DecimalUtil {
        public static double truncate(double value, int scale) {
            if (scale < 0) scale = 0;
            BigDecimal bd = BigDecimal.valueOf(value);
            bd = bd.setScale(scale, RoundingMode.DOWN);
            return bd.doubleValue();
        }
        
        public static String formatFull(double value) {
            if (value == 0) return "0";
            
            // Remove trailing zeros
            BigDecimal bd = BigDecimal.valueOf(value).stripTrailingZeros();
            return bd.toPlainString();
        }
    }
    
    /**
     * ApiClient - HTTP client for Coin API
     */
    public static class ApiClient {
        private final String baseUrl;
        private final int timeoutMs;
        private final Logger log;
    
        public static class CardTransferResult {
            public final boolean success;
            public final String txId;
            public final String raw;
    
            public CardTransferResult(boolean success, String txId, String raw) {
                this.success = success; this.txId = txId; this.raw = raw;
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
    
        public ApiClient(String baseUrl, int timeoutMs, Logger logger) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl : (baseUrl + "/");
            this.timeoutMs = timeoutMs;
            this.log = logger;
        }
    
        public CardTransferResult transferByCard(String fromCard, String toCard, double amount) {
            String endpoint = baseUrl + "api/card/pay";
            String body = "{\"fromCard\":\"" + esc(fromCard) + "\",\"toCard\":\"" + esc(toCard) + "\",\"amount\":" + amount + "}";
            try {
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
            String endpoint = baseUrl + "api/card/info";
            String body = "{\"cardCode\":\"" + esc(cardCode) + "\"}";
            try {
                String resp = postJson(endpoint, body);
                boolean success = parseBoolean(resp, "success");
                if (!success) return new CardInfoResult(false, null, parseString(resp, "error"));
                
                Double coins = parseDouble(resp, "coins");
                if (coins == null) coins = parseDouble(resp, "sats");
                return new CardInfoResult(true, coins, null);
            } catch (IOException e) {
                log.warning("HTTP error getting card info: " + e.getMessage());
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
    
        // Simple JSON parsing methods
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
    }
    
    /**
     * CoinPlaceholderExpansion - Placeholder %coin_user% for PlaceholderAPI
     * Updates every 2 seconds with the player's card balance
     */
    public static class CoinPlaceholderExpansion extends PlaceholderExpansion {
        private final CoinCardPlugin plugin;
        private ApiClient api;
        private UserStore users;
        private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastUpdate = new ConcurrentHashMap<>();
        private final long CACHE_TTL_MS = 2000; // 2 seconds
        
        public CoinPlaceholderExpansion(CoinCardPlugin plugin, ApiClient api, UserStore users) {
            this.plugin = plugin;
            this.api = api;
            this.users = users;
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
            
            // Check cache
            Long last = lastUpdate.get(uuid);
            Double cached = balanceCache.get(uuid);
            
            if (cached != null && last != null && (now - last) < CACHE_TTL_MS) {
                return DecimalUtil.formatFull(cached);
            }
            
            // Update asynchronously
            updateBalanceAsync(uuid);
            
            // Return cached value or loading indicator
            return cached != null ? DecimalUtil.formatFull(cached) : "...";
        }
        
        private void updateBalanceAsync(UUID uuid) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String card = users.getCard(uuid);
                if (card == null || card.isEmpty()) {
                    balanceCache.put(uuid, 0.0);
                    lastUpdate.put(uuid, System.currentTimeMillis());
                    return;
                }
                
                ApiClient.CardInfoResult result = api.getCardInfo(card);
                if (result.success && result.coins != null) {
                    balanceCache.put(uuid, result.coins);
                } else {
                    balanceCache.put(uuid, 0.0);
                }
                lastUpdate.put(uuid, System.currentTimeMillis());
            });
        }
    }
    
    /**
     * CoinCommand - /coin command (now based only on Card)
     */
    public static class CoinCommand implements org.bukkit.command.CommandExecutor, org.bukkit.command.TabCompleter {
        private final CoinCardPlugin plugin;
        private final UserStore users;
        private Economy eco;
        private ApiClient api;
        private QueueService queue;
        private CooldownManager cooldowns;
        private ConfigManager cfg;
        
        // Color constants for easier maintenance
        private static final String YELLOW = ChatColor.YELLOW.toString();
        private static final String GRAY = ChatColor.GRAY.toString();
        private static final String GREEN = ChatColor.GREEN.toString();
        private static final String RED = ChatColor.RED.toString();
        private static final String AQUA = ChatColor.AQUA.toString();
        private static final String WHITE = ChatColor.WHITE.toString();
    
        public CoinCommand(CoinCardPlugin plugin, UserStore users, ApiClient api,
                          QueueService queue, CooldownManager cooldowns,
                          Economy eco, ConfigManager cfg) {
            this.plugin = plugin;
            this.users = users;
            this.api = api;
            this.queue = queue;
            this.cooldowns = cooldowns;
            this.eco = eco;
            this.cfg = cfg;
        }
    
        public void setApi(ApiClient api) { this.api = api; }
        public void setQueue(QueueService queue) { this.queue = queue; }
        public void setCooldowns(CooldownManager cooldowns) { this.cooldowns = cooldowns; }
        public void setConfig(ConfigManager cfg) { this.cfg = cfg; }
        
        /**
         * Gets a player's card by nickname (works for offline players)
         * @param nick Player's nickname
         * @return Card string or null if not found
         */
        private String getCardByNick(String nick) {
            // Try to find online player first
            Player onlinePlayer = Bukkit.getPlayerExact(nick);
            if (onlinePlayer != null) {
                String card = users.getCard(onlinePlayer.getUniqueId());
                if (card != null) return card;
            }
            
            // Try to find in database by nickname
            UUID uuid = users.findUUIDByNick(nick);
            if (uuid != null) {
                return users.getCard(uuid);
            }
            
            return null;
        }
        
        /**
         * Gets a player's name by card (for display purposes)
         * @param card Card string
         * @return Player name or "Unknown"
         */
        private String getPlayerNameByCard(String card) {
            if (card == null) return "Unknown";
            
            // This is a simple implementation - in a real scenario you might want to cache this
            if (card.equals(cfg.getServerCard())) {
                return "Server";
            }
            
            return "Player";
        }
    
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, String l, String[] a) {
            if (a.length == 0) {
                s.sendMessage(YELLOW + "/coin card [card] " + GRAY + "- View or set your Card");
                s.sendMessage(YELLOW + "/coin pay <player> <amount> " + GRAY + "- Pay using your Card");
                s.sendMessage(YELLOW + "/coin buy <coins> " + GRAY + "- Convert coins → vault");
                s.sendMessage(YELLOW + "/coin sell <vault> " + GRAY + "- Convert vault → coins");
                if (s.hasPermission("coin.admin")) {
                    s.sendMessage(RED + "/coin reload " + GRAY + "- Reload configuration");
                    s.sendMessage(RED + "/coin server pay <player> <amount> " + GRAY + "- Pay using Server Card");
                }
                return true;
            }
    
            String sub = a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload":
                    if (!s.hasPermission("coin.admin")) { 
                        s.sendMessage(RED + "No permission."); 
                        return true; 
                    }
                    plugin.reloadLocalConfig();
                    plugin.applyRuntimeConfig();
                    s.sendMessage(GREEN + "Configuration reloaded.");
                    return true;
    
                case "card":
                    if (!(s instanceof Player)) { 
                        s.sendMessage(RED + "Players only."); 
                        return true; 
                    }
                    Player p = (Player) s;
                    if (a.length == 1) {
                        String card = users.getCard(p.getUniqueId());
                        if (card == null) {
                            p.sendMessage(RED + "You don't have a card set. Use " + YELLOW + "/coin card <card>");
                        } else {
                            p.sendMessage(GREEN + "Your Card: " + YELLOW + card);
                        }
                    } else {
                        setCard(p, a[1]);
                    }
                    return true;
    
                case "pay":
                    if (!(s instanceof Player)) { 
                        s.sendMessage(RED + "Players only."); 
                        return true; 
                    }
                    if (a.length < 3) { 
                        s.sendMessage(RED + "Usage: " + YELLOW + "/coin pay <player> <amount>"); 
                        return true; 
                    }
                    payPlayer((Player) s, a[1], a[2]);
                    return true;
    
                case "buy":
                    if (!(s instanceof Player)) { 
                        s.sendMessage(RED + "Players only."); 
                        return true; 
                    }
                    if (a.length < 2) { 
                        s.sendMessage(RED + "Usage: " + YELLOW + "/coin buy <coins>"); 
                        return true; 
                    }
                    buyCoinsToVault((Player) s, a[1]);
                    return true;
    
                case "sell":
                    if (!(s instanceof Player)) { 
                        s.sendMessage(RED + "Players only."); 
                        return true; 
                    }
                    if (a.length < 2) { 
                        s.sendMessage(RED + "Usage: " + YELLOW + "/coin sell <vault>"); 
                        return true; 
                    }
                    sellVaultToCoins((Player) s, a[1]);
                    return true;
    
                case "server":
                    if (!s.hasPermission("coin.admin")) { 
                        s.sendMessage(RED + "No permission."); 
                        return true; 
                    }
                    if (a.length >= 2 && a[1].equalsIgnoreCase("pay")) {
                        if (a.length < 4) { 
                            s.sendMessage(RED + "Usage: " + YELLOW + "/coin server pay <player> <amount>"); 
                            return true; 
                        }
                        serverPay(s, a[2], a[3]);
                        return true;
                    }
                    s.sendMessage(RED + "Usage: " + YELLOW + "/coin server pay <player> <amount>");
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
            if (!cooldowns.checkAndStamp(p.getUniqueId())) {
                p.sendMessage(RED + "Please wait 1 second before next transaction.");
                return;
            }
    
            String fromCard = users.getCard(p.getUniqueId());
            if (fromCard == null || fromCard.isEmpty()) { 
                p.sendMessage(RED + "Set your Card first with " + YELLOW + "/coin card <card>" + RED + "."); 
                return; 
            }
            
            // Get target card (works for offline players!)
            String toCard = getCardByNick(targetName);
            if (toCard == null) {
                p.sendMessage(RED + "Target player doesn't have a card set or doesn't exist.");
                return;
            }
    
            double parsed;
            try { 
                parsed = Double.parseDouble(amountStr); 
            } catch (Exception e) { 
                p.sendMessage(RED + "Invalid amount."); 
                return; 
            }
            final double fAmount = DecimalUtil.truncate(Math.max(0.0, parsed), 8);
            if (fAmount <= 0) { 
                p.sendMessage(RED + "Invalid amount."); 
                return; 
            }
    
            final String fFromCard = fromCard;
            final String fToCard = toCard;
            final String fTargetName = targetName;
    
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                ApiClient.CardTransferResult r = api.transferByCard(fFromCard, fToCard, fAmount);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (r.success) {
                        p.sendMessage(GREEN + "You sent " + YELLOW + DecimalUtil.formatFull(fAmount) + 
                                     GREEN + " to " + YELLOW + fTargetName + GREEN + 
                                     ". Transaction: " + AQUA + (r.txId != null ? r.txId : "-"));
                        
                        // Try to notify if player is online
                        Player onlineTarget = Bukkit.getPlayerExact(fTargetName);
                        if (onlineTarget != null && onlineTarget.isOnline()) {
                            onlineTarget.sendMessage(GREEN + "You received " + YELLOW + DecimalUtil.formatFull(fAmount) + 
                                                    GREEN + " coins from " + YELLOW + p.getName() + 
                                                    GREEN + ". Transaction: " + AQUA + (r.txId != null ? r.txId : "-"));
                        }
                        
                        plugin.getLogger().info(p.getName() + " sent " + fAmount + " to " + fTargetName + 
                                               " (offline=" + (onlineTarget == null) + ") tx=" + r.txId);
                    } else {
                        p.sendMessage(RED + "Transaction failed. Invalid card or insufficient funds.");
                    }
                });
            });
        }
    
        private void buyCoinsToVault(Player p, String coinsStr) {
            if (!cooldowns.checkAndStamp(p.getUniqueId())) {
                p.sendMessage(RED + "Please wait 1 second before next transaction.");
                return;
            }
    
            String fromCard = users.getCard(p.getUniqueId());
            String serverCard = cfg.getServerCard();
            
            if (fromCard == null || fromCard.isEmpty()) { 
                p.sendMessage(RED + "Set your Card with " + YELLOW + "/coin card" + RED + "."); 
                return; 
            }
            if (serverCard == null || serverCard.isEmpty()) { 
                p.sendMessage(RED + "Invalid configuration (Server Card)."); 
                return; 
            }
    
            double parsedCoins;
            try { 
                parsedCoins = Double.parseDouble(coinsStr); 
            } catch (Exception e) { 
                p.sendMessage(RED + "Invalid amount."); 
                return; 
            }
            final double fCoins = DecimalUtil.truncate(Math.max(0.0, parsedCoins), 8);
            if (fCoins <= 0) { 
                p.sendMessage(RED + "Invalid amount."); 
                return; 
            }
    
            final double fVaultToPay = DecimalUtil.truncate(fCoins * cfg.getBuyVaultPerCoin(), 4);
    
            OfflinePlayer serverAcc = plugin.getServerVaultAccount();
            if (serverAcc == null) { 
                p.sendMessage(RED + "Invalid configuration (Server UUID)."); 
                return; 
            }
            
            if (eco.getBalance(serverAcc) < fVaultToPay) {
                p.sendMessage(RED + "Failed: insufficient server balance.");
                return;
            }
    
            final String fFromCard = fromCard;
            final String fServerCard = serverCard;
    
            queue.enqueue(() -> {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ApiClient.CardTransferResult r = api.transferByCard(fFromCard, fServerCard, fCoins);
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!r.success) {
                            p.sendMessage(RED + "Failed: Invalid card or insufficient coin balance.");
                            return;
                        }
    
                        eco.withdrawPlayer(serverAcc, fVaultToPay);
                        eco.depositPlayer(p, fVaultToPay);
    
                        String tx = (r.txId != null ? r.txId : "-");
                        p.sendMessage(GREEN + "Bought " + YELLOW + DecimalUtil.formatFull(fCoins) + 
                                     GREEN + " coins for " + YELLOW + DecimalUtil.formatFull(fVaultToPay) + 
                                     GREEN + " vault. Transaction: " + AQUA + tx);
                        plugin.getLogger().info("BUY " + p.getName() + " coins=" + fCoins + 
                                               " vault=" + fVaultToPay + " tx=" + tx);
                    });
                });
            });
        }
    
        private void sellVaultToCoins(Player p, String vaultStr) {
            if (!cooldowns.checkAndStamp(p.getUniqueId())) {
                p.sendMessage(RED + "Please wait 1 second before next transaction.");
                return;
            }
    
            String toCard = users.getCard(p.getUniqueId());
            String serverCard = cfg.getServerCard();
            
            if (toCard == null || toCard.isEmpty()) { 
                p.sendMessage(RED + "Set your Card with " + YELLOW + "/coin card" + RED + "."); 
                return; 
            }
            if (serverCard == null || serverCard.isEmpty()) { 
                p.sendMessage(RED + "Invalid configuration (Server Card)."); 
                return; 
            }
    
            double parsedVault;
            try { 
                parsedVault = Double.parseDouble(vaultStr); 
            } catch (Exception e) { 
                p.sendMessage(RED + "Invalid amount."); 
                return; 
            }
            final double fVault = DecimalUtil.truncate(Math.max(0.0, parsedVault), 4);
            if (fVault <= 0) { 
                p.sendMessage(RED + "Invalid amount."); 
                return; 
            }
    
            if (eco.getBalance(p) < fVault) {
                p.sendMessage(RED + "You don't have enough vault.");
                return;
            }
    
            final double fCoins = DecimalUtil.truncate(fVault * cfg.getSellCoinsPerVault(), 8);
            final String fToCard = toCard;
            final String fServerCard = serverCard;
    
            queue.enqueue(() -> {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ApiClient.CardTransferResult r = api.transferByCard(fServerCard, fToCard, fCoins);
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!r.success) {
                            p.sendMessage(RED + "Failed: Server card invalid or insufficient funds.");
                            return;
                        }
    
                        eco.withdrawPlayer(p, fVault);
                        OfflinePlayer serverAcc = plugin.getServerVaultAccount();
                        if (serverAcc != null) eco.depositPlayer(serverAcc, fVault);
    
                        String tx = (r.txId != null ? r.txId : "-");
                        p.sendMessage(GREEN + "Sold " + YELLOW + DecimalUtil.formatFull(fVault) + 
                                     GREEN + " vault for " + YELLOW + DecimalUtil.formatFull(fCoins) + 
                                     GREEN + " coins. Transaction: " + AQUA + tx);
                        
                        if (p.isOnline()) {
                            p.sendMessage(GREEN + "You received " + YELLOW + DecimalUtil.formatFull(fCoins) + 
                                         GREEN + " coins from server. Transaction: " + AQUA + tx);
                        }
                        
                        plugin.getLogger().info("SELL " + p.getName() + " vault=" + fVault + 
                                               " coins=" + fCoins + " tx=" + tx);
                    });
                });
            });
        }
    
        private void serverPay(org.bukkit.command.CommandSender s, String targetName, String amountStr) {
            String serverCard = cfg.getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                s.sendMessage(RED + "Invalid configuration (Server Card).");
                return;
            }
            
            // Get target card (works for offline players!)
            String toCard = getCardByNick(targetName);
            if (toCard == null) {
                s.sendMessage(RED + "Target player doesn't have a card set or doesn't exist.");
                return;
            }
    
            double parsed;
            try { 
                parsed = Double.parseDouble(amountStr); 
            } catch (Exception e) { 
                s.sendMessage(RED + "Invalid amount."); 
                return; 
            }
            final double fAmount = DecimalUtil.truncate(Math.max(0.0, parsed), 8);
            if (fAmount <= 0) { 
                s.sendMessage(RED + "Invalid amount."); 
                return; 
            }
    
            final String fServerCard = serverCard;
            final String fToCard = toCard;
            final String fTargetName = targetName;
    
            queue.enqueue(() -> {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    ApiClient.CardTransferResult r = api.transferByCard(fServerCard, fToCard, fAmount);
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (r.success) {
                            String tx = (r.txId != null ? r.txId : "-");
                            s.sendMessage(GREEN + "Server sent " + YELLOW + DecimalUtil.formatFull(fAmount) + 
                                         GREEN + " to " + YELLOW + fTargetName + GREEN + 
                                         ". Transaction: " + AQUA + tx);
                            
                            Player onlineTarget = Bukkit.getPlayerExact(fTargetName);
                            if (onlineTarget != null && onlineTarget.isOnline()) {
                                onlineTarget.sendMessage(GREEN + "You received " + YELLOW + DecimalUtil.formatFull(fAmount) + 
                                                        GREEN + " coins from server. Transaction: " + AQUA + tx);
                            }
                            
                            plugin.getLogger().info("SERVER PAY to " + fTargetName + 
                                                   " (offline=" + (onlineTarget == null) + ") amount=" + fAmount + " tx=" + tx);
                        } else {
                            s.sendMessage(RED + "Failed: Server card invalid or insufficient funds.");
                        }
                    });
                });
            });
        }
    
        @Override
        public List<String> onTabComplete(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, 
                                          String l, String[] a) {
            List<String> out = new ArrayList<>();
            
            if (a.length == 1) {
                // First argument: subcommands
                out.addAll(Arrays.asList("card", "pay", "buy", "sell"));
                if (s.hasPermission("coin.admin")) {
                    out.addAll(Arrays.asList("reload", "server"));
                }
                return filter(out, a[0]);
            }
            
            if (a.length == 2) {
                // Second argument
                if (a[0].equalsIgnoreCase("pay") || (a[0].equalsIgnoreCase("server") && a.length == 2)) {
                    // For tab completion, we can only show online players
                    // But the command will work with offline players too
                    out.addAll(Bukkit.getOnlinePlayers().stream()
                                     .map(Player::getName)
                                     .collect(Collectors.toList()));
                    return filter(out, a[1]);
                }
            }
            
            if (a.length == 3) {
                // Third argument
                if (a[0].equalsIgnoreCase("server") && a[1].equalsIgnoreCase("pay") && s.hasPermission("coin.admin")) {
                    // /coin server pay <player>
                    out.addAll(Bukkit.getOnlinePlayers().stream()
                                     .map(Player::getName)
                                     .collect(Collectors.toList()));
                    return filter(out, a[2]);
                }
            }
            
            return Collections.emptyList();
        }
    
        private List<String> filter(List<String> base, String token) {
            if (token == null || token.isEmpty()) return base;
            String t = token.toLowerCase(Locale.ROOT);
            return base.stream()
                      .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(t))
                      .collect(Collectors.toList());
        }
    }
}
