package me.w1loz3r.anomaly;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class RiftDataStore {
    private final JavaPlugin plugin;
    private final File file;

    public RiftDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rift-state.yml");
    }

    public void save(Map<String, Material> changedBlocks, boolean riftBuilt, String world, int cx, int cy, int cz) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("rift.built", riftBuilt);
        yml.set("rift.world", world);
        yml.set("rift.center.x", cx);
        yml.set("rift.center.y", cy);
        yml.set("rift.center.z", cz);

        for (Map.Entry<String, Material> e : changedBlocks.entrySet()) {
            yml.set("blocks." + e.getKey(), e.getValue().name());
        }

        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save rift-state.yml: " + ex.getMessage());
        }
    }

    public LoadedRiftState load() {
        LoadedRiftState out = new LoadedRiftState();
        if (!file.exists()) return out;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        out.riftBuilt = yml.getBoolean("rift.built", false);
        out.world = yml.getString("rift.world", "world");
        out.cx = yml.getInt("rift.center.x", 0);
        out.cy = yml.getInt("rift.center.y", 0);
        out.cz = yml.getInt("rift.center.z", 0);

        if (yml.isConfigurationSection("blocks")) {
            for (String key : yml.getConfigurationSection("blocks").getKeys(false)) {
                String matName = yml.getString("blocks." + key, "STONE");
                Material mat = Material.matchMaterial(matName);
                if (mat != null) out.changedBlocks.put(key, mat);
            }
        }
        return out;
    }

    public void clear() {
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Failed to delete rift-state.yml");
        }
    }

    public static final class LoadedRiftState {
        public boolean riftBuilt = false;
        public String world = "world";
        public int cx, cy, cz;
        public Map<String, Material> changedBlocks = new HashMap<>();
    }
}
