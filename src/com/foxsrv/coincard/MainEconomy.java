package com.foxsrv.coincard;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MainEconomy – Main Vault economy implementation (Main: true).
 * 
 * Features:
 * - Immediate return to Vault (non-blocking), cache updated instantly.
 * - Transactions queued and processed in background with infinite retry.
 * - Pending transactions persisted in pending.yml (managed by the plugin).
 * - If a withdrawal fails due to INSUFFICIENT_FUNDS, CoinCardPlugin forces transfer of the remainder.
 * - Player lock, cooldown and busy state for atomicity.
 * - Notification via completeFuture to release busy state and update balance.
 * - Uses a txId map to reliably locate transaction info even after queue removal.
 * - Fully compatible with Folia.
 */
public class MainEconomy implements Economy {

    private final CoinCardPlugin plugin;
    private final Set<UUID> busyPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, TransactionInfo> pendingTxInfo = new ConcurrentHashMap<>();

    private static class TransactionInfo {
        final UUID uuid;
        final String card;
        final boolean isWithdraw;

        TransactionInfo(UUID uuid, String card, boolean isWithdraw) {
            this.uuid = uuid;
            this.card = card;
            this.isWithdraw = isWithdraw;
        }
    }

    public MainEconomy(CoinCardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

@Override
public String getName() {
    // Fallback seguro caso o disguise ainda não tenha sido inicializado
    if (plugin.getDisguise() == null) {
        return "CoinCard";
    }
    return plugin.getDisguise().getEconomyName();
}

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return plugin.getCoinConfig().getDecimals();
    }

    @Override
    public String format(double amount) {
        return CoinCardPlugin.DecimalUtil.formatDisplayValue(amount);
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

    // ==================== BALANCE ====================

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return getBalance(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        UUID uuid = player.getUniqueId();
        String card = plugin.getUserStore().getCard(uuid);
        if (card == null) return 0.0;

        // If player is busy, return 0 to prevent duplication
        if (isPlayerBusy(card, uuid)) {
            return 0.0;
        }

        Double internalBal = plugin.getBalanceCache().getBalanceFast(card);
        return CoinCardPlugin.DecimalUtil.toDisplay(internalBal != null ? internalBal : 0.0);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    // ==================== HAS ====================

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

    // ==================== WITHDRAW (IMMEDIATE RETURN) ====================

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

        ReentrantLock lock = plugin.getPlayerLock(uuid);
        if (!lock.tryLock()) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Another transaction in progress");
        }

        try {
            // Atomic checks inside lock
            if (isPlayerBusy(card, uuid)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Operation on cooldown");
            }
            if (plugin.withdrawPendingCards.contains(card)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Withdraw already pending");
            }

            double internalAmount = CoinCardPlugin.DecimalUtil.toInternal(amount);
            Double currentInternal = plugin.getBalanceCache().getBalanceFast(card);
            if (currentInternal == null) currentInternal = 0.0;
            double currentDisplay = CoinCardPlugin.DecimalUtil.toDisplay(currentInternal);

            if (currentDisplay < amount) {
                return new EconomyResponse(0, currentDisplay, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
            }

            String serverCard = plugin.getCoinConfig().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                return new EconomyResponse(0, currentDisplay, EconomyResponse.ResponseType.FAILURE, "Server card not set");
            }

            // Mark as busy and update cooldown
            busyPlayers.add(uuid);
            plugin.updateCooldown(uuid);

            // Subtract from cache instantly (available balance)
            double newInternal = currentInternal - internalAmount;
            plugin.getBalanceCache().setBalance(card, newInternal);
            plugin.withdrawPendingCards.add(card);

            // Create transaction and queue it
            CoinCardPlugin.VaultWithdrawTransaction tx = new CoinCardPlugin.VaultWithdrawTransaction(
                    uuid, card, serverCard, internalAmount, amount, currentInternal
            );
            pendingTxInfo.put(tx.txId, new TransactionInfo(uuid, card, true));
            plugin.pendingWithdraws.computeIfAbsent(uuid, k -> new ConcurrentLinkedQueue<>()).offer(tx);
            if (plugin.pendingStore != null) plugin.pendingStore.save();
            plugin.processWithdrawQueue(uuid);

            // Return SUCCESS immediately (transaction will be processed in background)
            double newDisplay = CoinCardPlugin.DecimalUtil.toDisplay(newInternal);
            return new EconomyResponse(amount, newDisplay, EconomyResponse.ResponseType.SUCCESS, null);

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

    // ==================== DEPOSIT (IMMEDIATE RETURN) ====================

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
            if (isPlayerBusy(card, uuid)) {
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Operation on cooldown");
            }

            busyPlayers.add(uuid);
            plugin.updateCooldown(uuid);

            double internalAmount = CoinCardPlugin.DecimalUtil.toInternal(amount);
            String serverCard = plugin.getCoinConfig().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                busyPlayers.remove(uuid);
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Server card not set");
            }

            if (plugin.withdrawPendingCards.contains(card)) {
                busyPlayers.remove(uuid);
                return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE,
                        "Cannot deposit while withdraw is pending");
            }

            Double currentInternal = plugin.getBalanceCache().getBalanceFast(card);
            if (currentInternal == null) currentInternal = 0.0;
            double newInternal = currentInternal + internalAmount;

            // Add to cache instantly
            plugin.getBalanceCache().setBalance(card, newInternal);

            // Create deposit transaction and queue it
            CoinCardPlugin.VaultDepositTransaction tx = new CoinCardPlugin.VaultDepositTransaction(
                    uuid, card, serverCard, internalAmount, amount
            );
            pendingTxInfo.put(tx.txId, new TransactionInfo(uuid, card, false));
            plugin.pendingDeposits.offer(tx);
            if (plugin.pendingStore != null) plugin.pendingStore.save();
            plugin.processDepositQueue();

            double newDisplay = CoinCardPlugin.DecimalUtil.toDisplay(newInternal);
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

    // ==================== COMPLETION NOTIFICATION ====================

    public void completeFuture(String txId, CoinCardPlugin.ApiClient.CardTransferResult result) {
        TransactionInfo info = pendingTxInfo.remove(txId);
        if (info == null) {
            plugin.getLogger().warning("completeFuture called with unknown txId: " + txId);
            return;
        }

        // Release busy state
        busyPlayers.remove(info.uuid);
        if (info.isWithdraw) {
            plugin.withdrawPendingCards.remove(info.card);
        }

        if (result.success) {
            // Sync balance with API
            CoinCardPlugin.ApiClient.CardInfoResult balance = plugin.getApiClient().getCardInfo(info.card);
            if (balance.success && balance.coins != null) {
                plugin.getBalanceCache().setBalance(info.card, balance.coins);
                plugin.getLogger().info("Transaction completed for " + info.uuid + " (" + info.card + "). New balance: " + balance.coins);
            } else {
                plugin.getLogger().warning("Could not fetch updated balance for " + info.uuid + " (" + info.card + ") after transaction.");
            }
        } else {
            plugin.getLogger().warning("Transaction failed for " + info.uuid + " (" + info.card + "): " + result.raw);
        }
    }

    // ==================== HELPER ====================

    private boolean isPlayerBusy(String card, UUID uuid) {
        return plugin.withdrawPendingCards.contains(card) ||
               busyPlayers.contains(uuid) ||
               plugin.isOnCooldown(uuid);
    }

    // ==================== BANKS (NOT SUPPORTED) ====================

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
