package com.foxsrv.coincard.io;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class UserStore {
    private final Plugin plugin;
    private final File file;
    private final YamlConfiguration yaml = new YamlConfiguration();

    public UserStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "users.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("users.yml", false);
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Falha ao carregar users.yml: " + e.getMessage());
        }
    }

    public void save() {
        try { yaml.save(file); } catch (IOException e) {
            plugin.getLogger().severe("Falha ao salvar users.yml: " + e.getMessage());
        }
    }

    private String basePath(UUID uuid) { return "Users." + uuid.toString(); }

    public void setNick(UUID uuid, String nick) {
        yaml.set(basePath(uuid) + ".nick", nick);
        save();
    }

    public void setID(UUID uuid, String id) {
        yaml.set(basePath(uuid) + ".ID", id);
        save();
    }

    public void setCard(UUID uuid, String card) {
        yaml.set(basePath(uuid) + ".Card", card);
        save();
    }

    public String getNick(UUID uuid) { return yaml.getString(basePath(uuid) + ".nick", null); }
    public String getID(UUID uuid) { return yaml.getString(basePath(uuid) + ".ID", null); }
    public String getCard(UUID uuid) { return yaml.getString(basePath(uuid) + ".Card", null); }

    public List<String> onlineRegisteredNicks(Collection<? extends org.bukkit.entity.Player> online) {
        Set<String> names = online.stream().map(p -> p.getName()).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (String key : yaml.getConfigurationSection("Users").getKeys(false)) {
            String path = "Users." + key + ".nick";
            String n = yaml.getString(path, null);
            if (n != null && names.contains(n)) out.add(n);
        }
        return out;
    }

    public UUID findUUIDByNick(String nick) {
        if (!yaml.isConfigurationSection("Users")) return null;
        for (String key : yaml.getConfigurationSection("Users").getKeys(false)) {
            String n = yaml.getString("Users." + key + ".nick", "");
            if (nick.equalsIgnoreCase(n)) {
                try { return UUID.fromString(key); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public String getIDByNick(String nick) {
        UUID u = findUUIDByNick(nick);
        if (u == null) return null;
        return getID(u);
    }
}
