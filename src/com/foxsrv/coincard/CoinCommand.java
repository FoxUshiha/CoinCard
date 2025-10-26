package com.foxsrv.coincard;

import com.foxsrv.coincard.core.*;
import com.foxsrv.coincard.io.ApiClient;
import com.foxsrv.coincard.io.UserStore;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

import static com.foxsrv.coincard.core.DecimalUtil.truncate;

public class CoinCommand implements CommandExecutor, TabCompleter {
    private final CoinCardPlugin plugin;
    private final UserStore users;
    private final Economy eco;

    // estes agora são atualizáveis via setters
    private ApiClient api;
    private QueueService queue;
    private CooldownManager cooldowns;
    private ConfigManager cfg;

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

    // ===== setters para receber instâncias novas após reload =====
    public void setApi(ApiClient api) { this.api = api; }
    public void setQueue(QueueService queue) { this.queue = queue; }
    public void setCooldowns(CooldownManager cooldowns) { this.cooldowns = cooldowns; }
    public void setConfig(ConfigManager cfg) { this.cfg = cfg; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§e/coin id <ID> §7- definir seu ID Coin");
            s.sendMessage("§e/coin card <CARD> §7- definir seu Card");
            s.sendMessage("§e/coin pay <ID|nick> <amount> §7- pagar via seu Card");
            s.sendMessage("§e/coin buy <coins> §7- converte coins->vault");
            s.sendMessage("§e/coin sell <vault> §7- converte vault->coins");
            if (s.hasPermission("coin.admin")) {
                s.sendMessage("§c/coin reload §7- recarrega configs e aplica");
                s.sendMessage("§c/coin server pay <ID|nick> <amount> §7- pagar via Card do servidor (fila)");
            }
            return true;
        }

        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                if (!s.hasPermission("coin.admin")) { s.sendMessage("§cSem permissão."); return true; }
                // 1) re-carrega o arquivo no plugin
                plugin.reloadLocalConfig();
                // 2) aplica runtime (recria api, cooldown, queue e atualiza este comando)
                plugin.applyRuntimeConfig();
                s.sendMessage("§aConfig recarregada e aplicada.");
                return true;

            case "id":
                if (!(s instanceof Player)) { s.sendMessage("Somente players."); return true; }
                if (a.length < 2) { s.sendMessage("Uso: /coin id <ID>"); return true; }
                setID((Player) s, a[1]);
                return true;

            case "card":
                if (!(s instanceof Player)) { s.sendMessage("Somente players."); return true; }
                if (a.length < 2) { s.sendMessage("Uso: /coin card <CARD>"); return true; }
                setCard((Player) s, a[1]);
                return true;

            case "pay":
                if (!(s instanceof Player)) { s.sendMessage("Somente players."); return true; }
                if (a.length < 3) { s.sendMessage("Uso: /coin pay <ID|nick> <amount>"); return true; }
                payUser((Player) s, a[1], a[2]);
                return true;

            case "buy":
                if (!(s instanceof Player)) { s.sendMessage("Somente players."); return true; }
                if (a.length < 2) { s.sendMessage("Uso: /coin buy <coins>"); return true; }
                buyCoinsToVault((Player) s, a[1]);
                return true;

            case "sell":
                if (!(s instanceof Player)) { s.sendMessage("Somente players."); return true; }
                if (a.length < 2) { s.sendMessage("Uso: /coin sell <vault>"); return true; }
                sellVaultToCoins((Player) s, a[1]);
                return true;

            case "server":
                if (!s.hasPermission("coin.admin")) { s.sendMessage("§cSem permissão."); return true; }
                if (a.length >= 2 && a[1].equalsIgnoreCase("pay")) {
                    if (a.length < 4) { s.sendMessage("Uso: /coin server pay <ID|nick> <amount>"); return true; }
                    serverPay(s, a[2], a[3]);
                    return true;
                }
                s.sendMessage("Uso: /coin server pay <ID|nick> <amount>");
                return true;
        }
        return false;
    }

    // /coin id <ID>
    private void setID(Player p, String id) {
        users.setNick(p.getUniqueId(), p.getName());
        users.setID(p.getUniqueId(), id);
        p.sendMessage("§aSeu ID foi definido para: §e" + id);
    }

    // /coin card <CARD>
    private void setCard(Player p, String card) {
        users.setNick(p.getUniqueId(), p.getName());
        users.setCard(p.getUniqueId(), card);
        p.sendMessage("§aSeu Card foi definido.");
    }

    // /coin pay <ID|nick> <amount> (usa card do jogador)
    private void payUser(Player p, String to, String amountStr) {
        if (!cooldowns.checkAndStamp(p.getUniqueId())) {
            p.sendMessage("§cAguarde 1s para nova transação.");
            return;
        }
        String card = users.getCard(p.getUniqueId());
        if (card == null || card.isEmpty()) { p.sendMessage("§cDefina seu Card com /coin card."); return; }

        String toId = resolveToId(to);
        if (toId == null) { p.sendMessage("§cDestino inválido ou usuário sem ID cadastrado."); return; }

        double parsed;
        try { parsed = Double.parseDouble(amountStr); } catch (Exception e) { p.sendMessage("§cValor inválido."); return; }
        final double fAmount = truncate(Math.max(0.0, parsed), 8);
        if (fAmount <= 0) { p.sendMessage("§cValor inválido."); return; }

        final String fCard = card;
        final String fToId = toId;
        final String fToLabel = to;

        Bukkit.getScheduler().runTaskAsynchronously(CoinCardPlugin.get(), () -> {
            ApiClient.CardTransferResult r = api.transferByCard(fCard, fToId, fAmount);
            if (r.success) {
                p.sendMessage("§aYou sent §e" + fAmount + "§a to §e" + fToLabel + "§a. Transaction ID: §b" + (r.txId != null ? r.txId : "-"));
                Player target = Bukkit.getPlayerExact(fToLabel);
                if (target != null && target.isOnline()) {
                    target.sendMessage("§aYou received §e" + fAmount + "§a coins from §e" + p.getName() + "§a. Transaction ID: §b" + (r.txId != null ? r.txId : "-"));
                }
                Bukkit.getConsoleSender().sendMessage("[CoinCard] " + p.getName() + " sent " + fAmount + " to " + fToLabel + " tx=" + r.txId);
            } else {
                p.sendMessage("§cOperation failed. Invalid Card or not enough funds.");
            }
        });
    }

    // /coin buy <coins>  (coins -> vault)
    private void buyCoinsToVault(Player p, String coinsStr) {
        if (!cooldowns.checkAndStamp(p.getUniqueId())) {
            p.sendMessage("§cAguarde 1s para nova transação.");
            return;
        }
        String card = users.getCard(p.getUniqueId());
        String serverId = cfg.getServerCoinID();
        if (card == null || card.isEmpty()) { p.sendMessage("§cDefina seu Card com /coin card."); return; }
        if (serverId == null || serverId.isEmpty()) { p.sendMessage("§cConfig inválida (ServerID)."); return; }

        double parsedCoins;
        try { parsedCoins = Double.parseDouble(coinsStr); } catch (Exception e) { p.sendMessage("§cValor inválido."); return; }
        final double fCoins = truncate(Math.max(0.0, parsedCoins), 8);
        if (fCoins <= 0) { p.sendMessage("§cValor inválido."); return; }

        final double fVaultToPay = truncate(fCoins * cfg.getBuyVaultPerCoin(), 4);

        OfflinePlayer serverAcc = plugin.getServerVaultAccount();
        if (serverAcc == null) { p.sendMessage("§cConfig inválida (Server UUID)."); return; }
        if (eco.getBalance(serverAcc) < fVaultToPay) {
            p.sendMessage("§cOperation failed, low server balance.");
            return;
        }

        final String fCard = card;

        queue.enqueue(() -> {
            Bukkit.getScheduler().runTaskAsynchronously(CoinCardPlugin.get(), () -> {
                ApiClient.CardTransferResult r = api.transferByCard(fCard, cfg.getServerCoinID(), fCoins);
                if (!r.success) {
                    p.sendMessage("§cOperation failed. Invalid Card or not enough funds in your coin balance.");
                    return;
                }

                eco.withdrawPlayer(serverAcc, fVaultToPay);
                eco.depositPlayer(p, fVaultToPay);

                String tx = (r.txId != null ? r.txId : "-");
                p.sendMessage("§aYou bought §e" + fCoins + "§a coins for §e" + fVaultToPay + "§a vault. Transaction ID: §b" + tx);
                Bukkit.getConsoleSender().sendMessage("[CoinCard] BUY " + p.getName() + " coins=" + fCoins + " vault=" + fVaultToPay + " tx=" + tx);
            });
        });
    }

    // /coin sell <vault> (vault -> coins)
    private void sellVaultToCoins(Player p, String vaultStr) {
        if (!cooldowns.checkAndStamp(p.getUniqueId())) {
            p.sendMessage("§cAguarde 1s para nova transação.");
            return;
        }
        String userId = users.getID(p.getUniqueId());
        if (userId == null || userId.isEmpty()) { p.sendMessage("§cDefina seu ID com /coin id <ID>."); return; }

        double parsedVault;
        try { parsedVault = Double.parseDouble(vaultStr); } catch (Exception e) { p.sendMessage("§cValor inválido."); return; }
        final double fVault = truncate(Math.max(0.0, parsedVault), 4);
        if (fVault <= 0) { p.sendMessage("§cValor inválido."); return; }

        if (eco.getBalance(p) < fVault) {
            p.sendMessage("§cYou do not have enough of money.");
            return;
        }

        final double fCoins = truncate(fVault * cfg.getSellCoinsPerVault(), 8);
        final String fUserId = userId;
        final String fUserName = p.getName();

        queue.enqueue(() -> {
            Bukkit.getScheduler().runTaskAsynchronously(CoinCardPlugin.get(), () -> {
                ApiClient.CardTransferResult r = api.transferByCard(cfg.getServerCard(), fUserId, fCoins);
                if (!r.success) {
                    p.sendMessage("§cOperation failed. Invalid Card of no enough of founds in your coin balance.");
                    return;
                }

                eco.withdrawPlayer(p, fVault);
                OfflinePlayer serverAcc = plugin.getServerVaultAccount();
                if (serverAcc != null) eco.depositPlayer(serverAcc, fVault);

                String tx = (r.txId != null ? r.txId : "-");
                p.sendMessage("§aYou sold §e" + fVault + "§a vault for §e" + fCoins + "§a coins. Transaction ID: §b" + tx);
                Bukkit.getConsoleSender().sendMessage("[CoinCard] SELL " + fUserName + " vault=" + fVault + " coins=" + fCoins + " tx=" + tx);
                Player target = Bukkit.getPlayerExact(fUserName);
                if (target != null && target.isOnline()) {
                    target.sendMessage("§aYou received §e" + fCoins + "§a coins from §eServer§a. Transaction ID: §b" + tx);
                }
            });
        });
    }

    // /coin server pay <ID|nick> <amount>
    private void serverPay(CommandSender s, String to, String amountStr) {
        String toId = resolveToId(to);
        if (toId == null) { s.sendMessage("§cDestino inválido ou sem ID."); return; }

        double parsed;
        try { parsed = Double.parseDouble(amountStr); } catch (Exception e) { s.sendMessage("§cValor inválido."); return; }
        final double fAmount = truncate(Math.max(0.0, parsed), 8);
        if (fAmount <= 0) { s.sendMessage("§cValor inválido."); return; }

        final String fToId = toId;
        final String fToLabel = to;

        queue.enqueue(() -> {
            Bukkit.getScheduler().runTaskAsynchronously(CoinCardPlugin.get(), () -> {
                ApiClient.CardTransferResult r = api.transferByCard(cfg.getServerCard(), fToId, fAmount);
                if (r.success) {
                    String tx = (r.txId != null ? r.txId : "-");
                    s.sendMessage("§aYou sent §e" + fAmount + "§a to §e" + fToLabel + "§a. Transaction ID: §b" + tx);
                    Player pt = Bukkit.getPlayerExact(fToLabel);
                    if (pt != null && pt.isOnline()) {
                        pt.sendMessage("§aYou received §e" + fAmount + "§a coins from §eServer§a. Transaction ID: §b" + tx);
                    }
                    Bukkit.getConsoleSender().sendMessage("[CoinCard] SERVER PAY to " + fToLabel + " amount=" + fAmount + " tx=" + tx);
                } else {
                    s.sendMessage("§cOperation failed. Invalid Card of no enough of founds.");
                }
            });
        });
    }

    private String resolveToId(String arg) {
        if (arg.matches("^\\d{6,}$")) return arg; // ID direto
        String idByNick = users.getIDByNick(arg);
        if (idByNick != null && !idByNick.isEmpty()) return idByNick;
        Player p = Bukkit.getPlayerExact(arg);
        if (p != null) {
            String id = users.getID(p.getUniqueId());
            if (id != null && !id.isEmpty()) return id;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(Arrays.asList("id","card","pay","buy","sell"));
            if (s.hasPermission("coin.admin")) out.addAll(Arrays.asList("reload","server"));
            return filter(out, a[0]);
        }
        if (a.length == 2) {
            if (a[0].equalsIgnoreCase("pay")) {
                out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                return filter(out, a[1]);
            }
            if (a[0].equalsIgnoreCase("server") && s.hasPermission("coin.admin")) {
                return filter(Collections.singletonList("pay"), a[1]);
            }
        }
        if (a.length == 3) {
            if (a[0].equalsIgnoreCase("server") && a[1].equalsIgnoreCase("pay") && s.hasPermission("coin.admin")) {
                out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()));
                return filter(out, a[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> base, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return base.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(t)).collect(Collectors.toList());
    }
}
