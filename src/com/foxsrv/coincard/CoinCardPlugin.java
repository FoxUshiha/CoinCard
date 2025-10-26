package com.foxsrv.coincard;

import com.foxsrv.coincard.core.*;
import com.foxsrv.coincard.io.ApiClient;
import com.foxsrv.coincard.io.UserStore;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class CoinCardPlugin extends JavaPlugin {
    private static CoinCardPlugin instance;

    private Economy economy;
    private ConfigManager config;
    private UserStore users;
    private ApiClient api;
    private QueueService queue;
    private CooldownManager cooldowns;

    // manter referência do comando para atualizações no reload
    private CoinCommand coinCommand;

    public static CoinCardPlugin get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        reloadLocalConfig(); // cria this.config

        if (!hookVault()) {
            getLogger().severe("Vault não encontrado. Desabilitando plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        users = new UserStore(this);
        users.load();

        // instâncias a partir da config atual
        api = new ApiClient(config.getApiBase(), config.getTimeoutMs(), getLogger());
        queue = new QueueService(this, config.getQueueIntervalTicks());
        cooldowns = new CooldownManager(config.getPerUserCooldownMs());

        // Comando principal (guarda referência)
        coinCommand = new CoinCommand(this, users, api, queue, cooldowns, economy, config);
        getCommand("coin").setExecutor(coinCommand);
        getCommand("coin").setTabCompleter(coinCommand);

        getLogger().info("CoinCard habilitado.");
    }

    @Override
    public void onDisable() {
        if (users != null) users.save();
        if (queue != null) queue.shutdown();
        getLogger().info("CoinCard desabilitado.");
    }

    public void reloadLocalConfig() {
        reloadConfig();
        this.config = new ConfigManager(getConfig());
    }

    /**
     * Aplica alterações de runtime após reload de config:
     * - recria ApiClient (API/timeout)
     * - recria CooldownManager (cooldown ms)
     * - recria QueueService se o intervalo mudou
     * - recarrega users.yml
     * - atualiza referências dentro do CoinCommand
     */
    public void applyRuntimeConfig() {
        // Recarregar users.yml do disco (caso alterado)
        if (users == null) users = new UserStore(this);
        users.load();

        // Recriar ApiClient com base/timeout novos
        this.api = new ApiClient(config.getApiBase(), config.getTimeoutMs(), getLogger());

        // Recriar CooldownManager
        this.cooldowns = new CooldownManager(config.getPerUserCooldownMs());

        // Recriar QueueService somente se o intervalo mudou
        int newInterval = config.getQueueIntervalTicks();
        boolean needNewQueue = (queue == null);
        if (!needNewQueue) {
            // não há getter no QueueService; se quiser, recrie sempre:
            // simples e seguro: sempre recriar a fila
            needNewQueue = true;
        }
        if (needNewQueue) {
            if (queue != null) queue.shutdown();
            queue = new QueueService(this, newInterval);
        }

        // Atualizar o comando com os novos objetos/config
        if (coinCommand != null) {
            coinCommand.setConfig(config);
            coinCommand.setApi(api);
            coinCommand.setQueue(queue);
            coinCommand.setCooldowns(cooldowns);
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

    public Economy getEconomy() { return economy; }
    public ConfigManager getCoinConfig() { return config; }
    public ApiClient getApi() { return api; }
    public QueueService getQueue() { return queue; }
    public CooldownManager getCooldowns() { return cooldowns; }

    public OfflinePlayer getServerVaultAccount() {
        try {
            UUID uuid = UUID.fromString(config.getServerVaultUUID());
            return Bukkit.getOfflinePlayer(uuid);
        } catch (Exception e) {
            return null;
        }
    }
}
