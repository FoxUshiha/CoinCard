package com.foxsrv.coincard;

import com.foxsrv.coincard.CoinCardPlugin.ApiClient;
import com.foxsrv.coincard.CoinCardPlugin.UserStore;
import com.foxsrv.coincard.CoinCardPlugin.ConfigManager;
import com.foxsrv.coincard.CoinCardPlugin.DecimalUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * AutoClaim – Automatic claim system for all players with cards.
 * <p>
 * Runs every 5 minutes (configurable via intervalMinutes), checks each player's card status via /api/card/info,
 * and if cooldown is ready, executes /api/card/claim.
 * After a successful claim, a configurable tax (ClaimTax) is transferred to the server card.
 * Players are notified in chat when their auto-claim occurs.
 * <p>
 * Fully asynchronous, respects per-user cooldown, and uses the plugin's existing ApiClient.
 */
public final class AutoClaim implements Runnable {

    private final CoinCardPlugin plugin;
    private final ApiClient apiClient;
    private final UserStore userStore;
    private final ConfigManager config;
    private final double claimTax;          // percentage (e.g. 0.01 = 1%)
    private final long perPlayerDelayMs;    // from config PerUserCooldownMs
    private final long intervalMinutes = 5; // cycle interval in minutes
    private final String serverCard;

    private ScheduledTask scheduledTask;
    private final Map<UUID, Boolean> processingPlayers = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private boolean running = false;

    // Internal API endpoints (relative to baseUrl)
    private static final String CARD_INFO_ENDPOINT = "api/card/info";
    private static final String CARD_CLAIM_ENDPOINT = "api/card/claim";

    /**
     * Constructor.
     *
     * @param plugin the main plugin instance
     */
    public AutoClaim(CoinCardPlugin plugin) {
        this.plugin = plugin;
        this.apiClient = plugin.getApiClient();
        this.userStore = plugin.getUserStore();
        this.config = plugin.getCoinConfig();
        this.claimTax = config != null ? config.getClaimTax() : 0.01;
        this.perPlayerDelayMs = config != null ? config.getPerUserCooldownMs() : 1010L;
        this.serverCard = config != null ? config.getServerCard() : null;
    }

    /**
     * Returns the base URL with a trailing slash.
     */
    private String getBaseUrl() {
        String base = config.getApiBase();
        if (base == null || base.isEmpty()) return "";
        return base.endsWith("/") ? base : base + "/";
    }

    /**
     * Starts the auto-claim scheduler.
     * Should be called from CoinCardPlugin.onEnable().
     */
    public void start() {
        if (running) return;
        if (serverCard == null || serverCard.isEmpty()) {
            plugin.getLogger().warning("AutoClaim: Server card not set – disabling auto-claim.");
            return;
        }
        synchronized (lock) {
            if (running) return;
            running = true;
        }

        // Schedule every 'intervalMinutes' minutes
        long ticks = 20L * 60 * intervalMinutes;
        scheduledTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> {
                    if (!running) {
                        task.cancel();
                        return;
                    }
                    runAutoClaim();
                },
                20L * 60 * 1,  // start after 1 minute (give time for startup)
                ticks
        );

        plugin.getLogger().info("AutoClaim started – checking every " + intervalMinutes + " minutes, tax=" + (claimTax * 100) + "%");
    }

    /**
     * Stops the scheduler.
     */
    public void stop() {
        synchronized (lock) {
            running = false;
        }
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        processingPlayers.clear();
        plugin.getLogger().info("AutoClaim stopped.");
    }

    /**
     * Main runnable – iterates over all users and processes each card.
     */
    private void runAutoClaim() {
        if (!running) return;

        // Get all UUIDs from user store (snapshot to avoid concurrent modification)
        Set<UUID> allUsers;
        try {
            allUsers = new HashSet<>(userStore.getAllUsers());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "AutoClaim: Failed to get user list", e);
            return;
        }

        if (allUsers.isEmpty()) {
            plugin.getLogger().fine("AutoClaim: No users found.");
            return;
        }

        plugin.getLogger().info("AutoClaim: Starting cycle for " + allUsers.size() + " users.");

        // Process each user asynchronously, but with a delay between each to respect cooldown
        plugin.getAsyncExecutor().submit(() -> {
            long startTime = System.currentTimeMillis();
            int processed = 0;
            int claimed = 0;

            for (UUID uuid : allUsers) {
                if (!running) break;

                // Skip if already being processed (should not happen)
                if (processingPlayers.putIfAbsent(uuid, Boolean.TRUE) != null) {
                    continue;
                }

                try {
                    String card = userStore.getCard(uuid);
                    if (card == null || card.isEmpty()) {
                        continue;
                    }

                    // Check cooldown status
                    ClaimStatus status = getClaimStatus(card);
                    if (status == null) {
                        // error or card not found
                        continue;
                    }

                    // If cooldown remaining > 0, skip
                    if (status.cooldownRemainingMs > 0) {
                        continue;
                    }

                    // Perform claim
                    double claimedAmount = performClaim(card);
                    if (claimedAmount <= 0) {
                        // claim failed or returned 0
                        continue;
                    }

                    // Apply tax
                    double taxAmount = claimedAmount * claimTax;
                    double playerAmount = claimedAmount - taxAmount;

                    // If tax is positive, transfer to server card
                    if (taxAmount > 0.00000001) { // tiny threshold
                        ApiClient.CardTransferResult transferResult =
                                apiClient.transferByCard(card, serverCard, taxAmount);
                        if (!transferResult.success) {
                            plugin.getLogger().warning("AutoClaim: Failed to transfer tax (" + taxAmount +
                                    ") from " + card + " to server card: " + transferResult.raw);
                            // Continue, but log error
                        }
                    }

                    // Update local balance cache
                    double newBalance = status.currentBalance - claimedAmount + playerAmount;
                    if (newBalance < 0) newBalance = 0;
                    plugin.getBalanceCache().setBalance(card, newBalance);

                    // Notify player
                    notifyPlayer(uuid, playerAmount);

                    // Log success
                    plugin.getLogger().info("AutoClaim: Claimed " + claimedAmount +
                            " for " + uuid + " (player got " + playerAmount + ", tax=" + taxAmount + ")");
                    claimed++;

                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "AutoClaim: Error processing user " + uuid, e);
                } finally {
                    processingPlayers.remove(uuid);
                    // Delay between each player to avoid rate-limiting
                    try {
                        Thread.sleep(perPlayerDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                processed++;
            }

            long duration = System.currentTimeMillis() - startTime;
            plugin.getLogger().info("AutoClaim: Cycle completed. Processed " + processed +
                    " users, claimed " + claimed + ". Duration: " + duration + "ms");
        });
    }

    // ==================== API HELPERS ====================

    /**
     * Represents the claim status for a card.
     */
    private static class ClaimStatus {
        final boolean success;
        final long cooldownRemainingMs;
        final double currentBalance;
        final String error;

        ClaimStatus(boolean success, long cooldownRemainingMs, double currentBalance, String error) {
            this.success = success;
            this.cooldownRemainingMs = cooldownRemainingMs;
            this.currentBalance = currentBalance;
            this.error = error;
        }
    }

    /**
     * Calls /api/card/info to get cooldown and balance.
     *
     * @param cardCode the card to query
     * @return ClaimStatus or null on error
     */
    private ClaimStatus getClaimStatus(String cardCode) {
        String endpoint = getBaseUrl() + CARD_INFO_ENDPOINT;
        String body = "{\"cardCode\":\"" + escapeJson(cardCode) + "\"}";

        try {
            String response = postJson(endpoint, body);
            if (response == null || response.isEmpty()) {
                return null;
            }

            boolean success = parseBoolean(response, "success");
            if (!success) {
                String error = parseString(response, "error");
                plugin.getLogger().fine("AutoClaim: card/info failed for " + cardCode + ": " + error);
                return new ClaimStatus(false, 0, 0, error);
            }

            long cooldownRemaining = parseLong(response, "cooldownRemainingMs");
            if (cooldownRemaining < 0) cooldownRemaining = 0;

            Double coins = parseDouble(response, "coins");
            if (coins == null) coins = parseDouble(response, "sats");
            if (coins == null) coins = 0.0;

            return new ClaimStatus(true, cooldownRemaining, DecimalUtil.truncate(coins, 8), null);

        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "AutoClaim: HTTP error getting status for " + cardCode, e);
            return null;
        }
    }

    /**
     * Calls /api/card/claim to perform the claim.
     *
     * @param cardCode the card to claim for
     * @return the amount claimed (internal coins), or -1 if failed
     */
    private double performClaim(String cardCode) {
        String endpoint = getBaseUrl() + CARD_CLAIM_ENDPOINT;
        String body = "{\"cardCode\":\"" + escapeJson(cardCode) + "\"}";

        try {
            String response = postJson(endpoint, body);
            if (response == null || response.isEmpty()) {
                return -1;
            }

            boolean success = parseBoolean(response, "success");
            if (!success) {
                String error = parseString(response, "error");
                plugin.getLogger().fine("AutoClaim: claim failed for " + cardCode + ": " + error);
                return -1;
            }

            // Parse claimed amount
            Double claimed = parseDouble(response, "claimed");
            if (claimed == null) {
                // Some APIs might return "amount" or "coins"
                claimed = parseDouble(response, "amount");
                if (claimed == null) claimed = parseDouble(response, "coins");
                if (claimed == null) {
                    plugin.getLogger().warning("AutoClaim: Claim response missing claimed amount for " + cardCode);
                    return -1;
                }
            }
            return DecimalUtil.truncate(claimed, 8);

        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "AutoClaim: HTTP error performing claim for " + cardCode, e);
            return -1;
        }
    }

    // ==================== NOTIFICATION ====================

    /**
     * Notifies a player (if online) about the auto-claim.
     *
     * @param uuid  player UUID
     * @param amount amount received (after tax), in internal coins
     */
    private void notifyPlayer(UUID uuid, double amount) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        // Format the amount according to display decimals
        String formatted = DecimalUtil.formatDisplay(amount);

        player.sendMessage(ChatColor.GREEN + "Auto-claim completed!");
        player.sendMessage(ChatColor.GRAY + "You received " + ChatColor.YELLOW + formatted +
                ChatColor.GRAY + " coins.");
    }

    // ==================== HTTP UTILITIES (copied from ApiClient for independence) ====================

    private String postJson(String urlStr, String json) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(config.getTimeoutMs());
        con.setReadTimeout(config.getTimeoutMs());
        con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = readAll(is);
        if (code >= 400) {
            plugin.getLogger().fine("AutoClaim: POST " + urlStr + " returned " + code + ": " + resp);
        }
        return resp != null ? resp : "";
    }

    private String readAll(InputStream is) throws IOException {
        if (is == null) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
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

    private Double parseDouble(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        String tail = json.substring(colon + 1).trim();
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == ',' || c == '}' || c == ']') break;
            if (Character.isDigit(c) || c == '.' || c == '-') num.append(c);
        }
        try {
            return Double.parseDouble(num.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long parseLong(String json, String key) {
        Double d = parseDouble(json, key);
        return d == null ? 0 : d.longValue();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== Runnable IMPLEMENTATION ====================

    @Override
    public void run() {
        runAutoClaim();
    }
}
