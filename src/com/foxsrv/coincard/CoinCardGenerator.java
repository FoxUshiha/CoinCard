package com.foxsrv.coincard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.foxsrv.coincard.CoinCardPlugin.CoinCardAPI;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * CoinCardGenerator - Automatic card generator for players without a card.
 * Fully asynchronous and Folia-compatible.
 * Stores generated data in new_users.yml.
 * 
 * This class is meant to be instantiated and managed by CoinCardPlugin.
 */
public final class CoinCardGenerator implements Listener, CommandExecutor, TabCompleter {

    private final CoinCardPlugin plugin;
    private YamlConfiguration usersConfig;
    private File usersFile;

    // Config values (read from CoinCardPlugin's config)
    private String apiBase;
    private String defaultPassword;
    private long cooldownMs;
    private int startSuffix;
    private int maxSuffixAttempts;

    // Track players currently being processed (prevent duplicate generation)
    private final Map<UUID, Boolean> processingPlayers = new ConcurrentHashMap<>();

    // Singleton instance (optional, for convenience)
    private static CoinCardGenerator instance;

    public static CoinCardGenerator getInstance() {
        return instance;
    }

    /**
     * Constructor – called by CoinCardPlugin.
     */
    public CoinCardGenerator(CoinCardPlugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    /**
     * Initializes the generator: loads config, loads users file, registers events/commands.
     * Must be called from CoinCardPlugin.onEnable() after the plugin is fully initialized.
     */
    public void init() {
        // Load configuration from CoinCardPlugin's config
        loadConfig();

        // Load users file (new_users.yml)
        loadUsersFile();

        // Register events and command executor
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("cardgen") != null) {
            plugin.getCommand("cardgen").setExecutor(this);
            plugin.getCommand("cardgen").setTabCompleter(this);
        } else {
            plugin.getLogger().warning("Command 'cardgen' not found in plugin.yml – generator commands will not work.");
        }

        plugin.getLogger().info("CoinCardGenerator initialized. Cooldown: " + cooldownMs + "ms");
    }

    /**
     * Saves data on plugin disable – called from CoinCardPlugin.onDisable().
     */
    public void shutdown() {
        saveUsersFile();
        processingPlayers.clear();
        plugin.getLogger().info("CoinCardGenerator shut down.");
    }

    // ==================== CONFIGURATION (from CoinCardPlugin's config) ====================

    private void loadConfig() {
        CoinCardPlugin.ConfigManager cfg = plugin.getCoinConfig();
        if (cfg == null) {
            plugin.getLogger().warning("ConfigManager not available – using fallback defaults.");
            apiBase = "https://bank.foxsrv.net/";
            defaultPassword = "1234";
            cooldownMs = 1010L;
            startSuffix = 2;
            maxSuffixAttempts = 10;
            return;
        }

        apiBase = cfg.getApiBase();
        defaultPassword = cfg.getDefaultPassword();
        cooldownMs = cfg.getDefaultCooldown();
        startSuffix = cfg.getDefaultNameSpace();
        maxSuffixAttempts = cfg.getMaxSuffixAttempts();

        if (!apiBase.endsWith("/")) apiBase += "/";
    }

    // ==================== USER STORAGE (new_users.yml) ====================

    private void loadUsersFile() {
        usersFile = new File(plugin.getDataFolder(), "new_users.yml");
        if (!usersFile.exists()) {
            try {
                usersFile.createNewFile();
                plugin.getLogger().info("Created new_users.yml");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not create new_users.yml", e);
            }
        }
        usersConfig = YamlConfiguration.loadConfiguration(usersFile);
        if (!usersConfig.contains("Users")) {
            usersConfig.createSection("Users");
        }
    }

    private void saveUsersFile() {
        try {
            usersConfig.save(usersFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save new_users.yml", e);
        }
    }

    private void storeGeneratedPlayer(UUID uuid, String coinUser, String password, String card) {
        String path = "Users." + uuid.toString();
        usersConfig.set(path + ".CoinUser", coinUser);
        usersConfig.set(path + ".Password", password);
        usersConfig.set(path + ".Card", card);
        usersConfig.set(path + ".GeneratedAt", System.currentTimeMillis());
        saveUsersFile();
    }

    private boolean hasGeneratedEntry(UUID uuid) {
        return usersConfig.contains("Users." + uuid.toString());
    }

    private String getGeneratedCard(UUID uuid) {
        return usersConfig.getString("Users." + uuid.toString() + ".Card");
    }

    private String getGeneratedUsername(UUID uuid) {
        return usersConfig.getString("Users." + uuid.toString() + ".CoinUser");
    }

    // ==================== PLAYER JOIN EVENT ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Avoid duplicate processing
        if (processingPlayers.putIfAbsent(uuid, Boolean.TRUE) != null) {
            return;
        }

        // Run async (Folia AsyncScheduler)
        plugin.getServer().getAsyncScheduler().runNow(plugin, asyncTask -> {
            try {
                // Initial cooldown to avoid race conditions
                Thread.sleep(cooldownMs);

                CoinCardAPI api = CoinCardPlugin.getAPI();
                if (api == null) {
                    plugin.getLogger().warning("CoinCardAPI not available! Is CoinCardPlugin enabled?");
                    return;
                }

                // If player already has a card, skip
                if (api.hasCard(uuid)) {
                    plugin.getLogger().info("Player " + player.getName() + " already has a card. Skipping generation.");
                    return;
                }

                // Check if we already generated for this player (stored in new_users.yml)
                if (hasGeneratedEntry(uuid)) {
                    String existingCard = getGeneratedCard(uuid);
                    if (existingCard != null && !existingCard.isEmpty()) {
                        // Restore card (run on global scheduler to avoid async issues)
                        plugin.getServer().getGlobalRegionScheduler().run(plugin, run -> {
                            api.setPlayerCard(uuid, existingCard);
                        });
                        plugin.getLogger().info("Restored card for " + player.getName() + " from new_users.yml: " + existingCard);
                        return;
                    }
                }

                // Generate new username & card
                String baseName = player.getName();
                if (baseName.length() > 6) {
                    baseName = baseName.substring(0, 6);
                }

                String coinUser = null;
                String sessionId = null;
                String finalPassword = defaultPassword;

                // Try to register a unique username
                for (int attempt = 0; attempt < maxSuffixAttempts; attempt++) {
                    String candidate;
                    if (attempt == 0) {
                        candidate = baseName;
                    } else {
                        int suffixNum = startSuffix + attempt - 1;
                        candidate = baseName + String.format("%03d", suffixNum);
                    }

                    if (attempt > 0) {
                        Thread.sleep(cooldownMs);
                    }

                    ApiResult regResult = registerUser(candidate, finalPassword);
                    if (regResult.success) {
                        coinUser = candidate;
                        sessionId = regResult.sessionId;
                        plugin.getLogger().info("Registered " + player.getName() + " as " + coinUser);
                        break;
                    } else if (regResult.error != null && regResult.error.contains("already taken")) {
                        plugin.getLogger().info("Username " + candidate + " taken, trying next suffix.");
                        continue;
                    } else {
                        plugin.getLogger().warning("Registration failed for " + candidate + ": " + regResult.error);
                        return;
                    }
                }

                if (coinUser == null || sessionId == null) {
                    plugin.getLogger().warning("Could not register unique username for " + player.getName() + " after " + maxSuffixAttempts + " attempts.");
                    return;
                }

                // Get card code using session
                Thread.sleep(cooldownMs);
                String cardCode = getCardCode(sessionId);
                if (cardCode == null || cardCode.isEmpty()) {
                    plugin.getLogger().warning("Failed to obtain card code for " + player.getName());
                    return;
                }

                // Assign card to player (run on global scheduler to avoid async issues)
                Thread.sleep(cooldownMs);
                plugin.getServer().getGlobalRegionScheduler().run(plugin, run -> {
                    if (api != null) {
                        api.setPlayerCard(uuid, cardCode);
                        plugin.getLogger().info("Assigned card " + cardCode + " to " + player.getName());
                    } else {
                        plugin.getLogger().warning("CoinCardAPI missing, cannot assign card.");
                    }
                });

                // Store in new_users.yml
                storeGeneratedPlayer(uuid, coinUser, finalPassword, cardCode);

                // Notify player - create final variables for lambda
                final String finalCoinUser = coinUser;
                final String finalCardCode = cardCode;
                player.getScheduler().run(plugin, schedulerTask -> {
                    player.sendMessage(ChatColor.GREEN + "Your Coin Card has been automatically generated and linked.");
                    player.sendMessage(ChatColor.GRAY + "Username: " + ChatColor.YELLOW + finalCoinUser);
                    player.sendMessage(ChatColor.GRAY + "Card: " + ChatColor.YELLOW + finalCardCode);
                }, null);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                plugin.getLogger().warning("Generation interrupted for " + player.getName());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error generating card for " + player.getName(), e);
            } finally {
                processingPlayers.remove(uuid);
            }
        });
    }

    // ==================== API HELPERS ====================

    private static class ApiResult {
        boolean success;
        String sessionId;
        String error;

        ApiResult(boolean success, String sessionId, String error) {
            this.success = success;
            this.sessionId = sessionId;
            this.error = error;
        }
    }

    private ApiResult registerUser(String username, String password) {
        String endpoint = apiBase + "api/register";
        String json = "{\"username\":\"" + escapeJson(username) + "\",\"password\":\"" + escapeJson(password) + "\"}";
        try {
            String resp = postJson(endpoint, json);
            boolean success = parseBoolean(resp, "success");
            if (success) {
                String sessionId = parseString(resp, "sessionId");
                if (sessionId == null) sessionId = parseString(resp, "session_id");
                return new ApiResult(true, sessionId, null);
            } else {
                String errorMsg = parseString(resp, "error");
                if (errorMsg == null) errorMsg = "Registration failed";
                return new ApiResult(false, null, errorMsg);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("HTTP error during register: " + e.getMessage());
            return new ApiResult(false, null, "HTTP_ERROR");
        }
    }

    private String getCardCode(String sessionId) {
        String endpoint = apiBase + "api/card";
        String json = "{}";
        try {
            String resp = postJsonWithAuth(endpoint, json, sessionId);
            if (resp == null) return null;
            String cardCode = parseString(resp, "cardCode");
            if (cardCode == null) cardCode = parseString(resp, "card");
            return cardCode;
        } catch (IOException e) {
            plugin.getLogger().warning("HTTP error getting card: " + e.getMessage());
            return null;
        }
    }

    private String postJson(String urlStr, String json) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = readAll(is);
        if (code >= 400) {
            plugin.getLogger().warning("POST " + urlStr + " returned " + code + ": " + resp);
        }
        return resp != null ? resp : "";
    }

    private String postJsonWithAuth(String urlStr, String json, String token) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        con.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = readAll(is);
        if (code >= 400) {
            plugin.getLogger().warning("Auth POST " + urlStr + " returned " + code + ": " + resp);
            return null;
        }
        return resp;
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
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return false;
        String after = json.substring(colon + 1).trim();
        return after.startsWith("true");
    }

    private String parseString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== COMMANDS (CommandExecutor) ====================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("cardgen.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/cardgen reload - Reload config");
            sender.sendMessage(ChatColor.YELLOW + "/cardgen generate <player> - Force generate card for a player");
            sender.sendMessage(ChatColor.YELLOW + "/cardgen list - List generated users (console only)");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            // Reload config from CoinCardPlugin
            plugin.reloadLocalConfig();
            loadConfig();
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("generate") && args.length >= 2) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not online.");
                return true;
            }
            if (processingPlayers.containsKey(target.getUniqueId())) {
                sender.sendMessage(ChatColor.RED + "Already processing this player.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Generating card for " + target.getName() + "...");
            processingPlayers.put(target.getUniqueId(), Boolean.TRUE);
            plugin.getServer().getAsyncScheduler().runNow(plugin, asyncTask -> {
                try {
                    generateForPlayer(target);
                } finally {
                    processingPlayers.remove(target.getUniqueId());
                }
            });
            return true;
        }

        if (args[0].equalsIgnoreCase("list") && !(sender instanceof Player)) {
            // List all generated users (console only)
            if (usersConfig.contains("Users")) {
                Set<String> keys = usersConfig.getConfigurationSection("Users").getKeys(false);
                sender.sendMessage(ChatColor.GOLD + "=== Generated Users (" + keys.size() + ") ===");
                for (String key : keys) {
                    String path = "Users." + key;
                    String name = usersConfig.getString(path + ".CoinUser");
                    String card = usersConfig.getString(path + ".Card");
                    long time = usersConfig.getLong(path + ".GeneratedAt");
                    sender.sendMessage(ChatColor.YELLOW + key + " -> " + ChatColor.WHITE + name + " (card: " + card + ") " + ChatColor.GRAY + new Date(time));
                }
            } else {
                sender.sendMessage(ChatColor.RED + "No users found.");
            }
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Invalid subcommand.");
        return true;
    }

    private void generateForPlayer(Player player) {
        try {
            Thread.sleep(cooldownMs);
            CoinCardAPI api = CoinCardPlugin.getAPI();
            if (api == null) {
                plugin.getLogger().warning("API not available");
                return;
            }
            if (api.hasCard(player.getUniqueId())) {
                player.getScheduler().run(plugin, schedulerTask -> {
                    player.sendMessage(ChatColor.YELLOW + "Already has a card.");
                }, null);
                return;
            }

            String baseName = player.getName();
            if (baseName.length() > 6) baseName = baseName.substring(0, 6);
            String coinUser = null;
            String sessionId = null;

            for (int attempt = 0; attempt < maxSuffixAttempts; attempt++) {
                String candidate = (attempt == 0) ? baseName : baseName + String.format("%03d", startSuffix + attempt - 1);
                if (attempt > 0) Thread.sleep(cooldownMs);
                ApiResult reg = registerUser(candidate, defaultPassword);
                if (reg.success) {
                    coinUser = candidate;
                    sessionId = reg.sessionId;
                    break;
                } else if (reg.error != null && reg.error.contains("already taken")) {
                    continue;
                } else {
                    player.getScheduler().run(plugin, schedulerTask -> {
                        player.sendMessage(ChatColor.RED + "Registration error: " + reg.error);
                    }, null);
                    return;
                }
            }
            if (coinUser == null || sessionId == null) {
                player.getScheduler().run(plugin, schedulerTask -> {
                    player.sendMessage(ChatColor.RED + "Could not create unique username.");
                }, null);
                return;
            }

            Thread.sleep(cooldownMs);
            String cardCode = getCardCode(sessionId);
            if (cardCode == null) {
                player.getScheduler().run(plugin, schedulerTask -> {
                    player.sendMessage(ChatColor.RED + "Failed to obtain card.");
                }, null);
                return;
            }

            Thread.sleep(cooldownMs);
            plugin.getServer().getGlobalRegionScheduler().run(plugin, schedulerTask -> {
                api.setPlayerCard(player.getUniqueId(), cardCode);
            });
            storeGeneratedPlayer(player.getUniqueId(), coinUser, defaultPassword, cardCode);

            // Create final variables for lambda
            final String finalCoinUser = coinUser;
            final String finalCardCode = cardCode;
            player.getScheduler().run(plugin, schedulerTask -> {
                player.sendMessage(ChatColor.GREEN + "Card generated and set: " + finalCardCode);
                player.sendMessage(ChatColor.GRAY + "Username: " + ChatColor.YELLOW + finalCoinUser);
            }, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error in manual generation for " + player.getName(), e);
            player.getScheduler().run(plugin, schedulerTask -> {
                player.sendMessage(ChatColor.RED + "Internal error during generation.");
            }, null);
        }
    }

    // ==================== TAB COMPLETER ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(Arrays.asList("reload", "generate"));
            if (!(sender instanceof Player)) {
                list.add("list");
            }
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("generate")) {
            // Return online player names
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }
        return Collections.emptyList();
    }
}
