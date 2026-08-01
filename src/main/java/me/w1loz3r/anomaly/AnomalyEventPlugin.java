package me.w1loz3r.anomaly;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class AnomalyEventPlugin extends JavaPlugin {

    private int fogTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("AnomalyEvent enabled.");

        if (getConfig().getBoolean("state.anomaly-enabled")) {
            applyAnomalyState();
        }
    }

    @Override
    public void onDisable() {
        stopFogTask();
        getLogger().info("AnomalyEvent disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anomaly.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§e/anomaly on");
            sender.sendMessage("§e/anomaly off");
            sender.sendMessage("§e/anomaly storm <on|off>");
            sender.sendMessage("§e/anomaly night <on|off>");
            sender.sendMessage("§e/anomaly fog <on|off>");
            sender.sendMessage("§e/anomaly status");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "on" -> {
                getConfig().set("state.anomaly-enabled", true);
                getConfig().set("state.storm-enabled", true);
                getConfig().set("state.long-night-enabled", true);
                getConfig().set("effects.fog.enabled", true);
                saveConfig();

                applyAnomalyState();
                Bukkit.broadcastMessage("§5[Аномалия] §dРазлом открыт. Мир изменился...");
                sender.sendMessage("§aАномалия включена.");
                return true;
            }
            case "off" -> {
                getConfig().set("state.anomaly-enabled", false);
                getConfig().set("state.storm-enabled", false);
                getConfig().set("state.long-night-enabled", false);
                getConfig().set("effects.fog.enabled", false);
                saveConfig();

                clearAnomalyState();
                Bukkit.broadcastMessage("§5[Аномалия] §aРазлом закрыт. Мир успокаивается.");
                sender.sendMessage("§aАномалия выключена.");
                return true;
            }
            case "storm" -> {
                if (args.length < 2) {
                    sender.sendMessage("§eИспользуй: /anomaly storm <on|off>");
                    return true;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                getConfig().set("state.storm-enabled", on);
                saveConfig();
                applyStorm(on);
                sender.sendMessage(on ? "§aБуря включена." : "§aБуря выключена.");
                return true;
            }
            case "night" -> {
                if (args.length < 2) {
                    sender.sendMessage("§eИспользуй: /anomaly night <on|off>");
                    return true;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                getConfig().set("state.long-night-enabled", on);
                saveConfig();
                applyNight(on);
                sender.sendMessage(on ? "§aДлинная ночь включена." : "§aДлинная ночь выключена.");
                return true;
            }
            case "fog" -> {
                if (args.length < 2) {
                    sender.sendMessage("§eИспользуй: /anomaly fog <on|off>");
                    return true;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                getConfig().set("effects.fog.enabled", on);
                saveConfig();
                applyFog(on);
                sender.sendMessage(on ? "§aТуман включен." : "§aТуман выключен.");
                return true;
            }
            case "status" -> {
                sender.sendMessage("§7=== §dAnomaly Status §7===");
                sender.sendMessage("§7anomaly-enabled: §f" + getConfig().getBoolean("state.anomaly-enabled"));
                sender.sendMessage("§7storm-enabled: §f" + getConfig().getBoolean("state.storm-enabled"));
                sender.sendMessage("§7long-night-enabled: §f" + getConfig().getBoolean("state.long-night-enabled"));
                sender.sendMessage("§7fog-enabled: §f" + getConfig().getBoolean("effects.fog.enabled"));
                return true;
            }
            default -> {
                sender.sendMessage("§cНеизвестная подкоманда.");
                return true;
            }
        }
    }

    private void applyAnomalyState() {
        applyStorm(getConfig().getBoolean("state.storm-enabled"));
        applyNight(getConfig().getBoolean("state.long-night-enabled"));
        applyFog(getConfig().getBoolean("effects.fog.enabled"));
    }

    private void clearAnomalyState() {
        applyStorm(false);
        applyNight(false);
        applyFog(false);
    }

    private World targetWorld() {
        String worldName = getConfig().getString("world", "world");
        World w = Bukkit.getWorld(worldName);
        if (w == null && !Bukkit.getWorlds().isEmpty()) {
            w = Bukkit.getWorlds().get(0);
        }
        return w;
    }

    private void applyStorm(boolean enable) {
        World w = targetWorld();
        if (w == null) return;

        if (enable) {
            w.setStorm(true);
            w.setThundering(true);
            w.setWeatherDuration(Integer.MAX_VALUE);
            w.setThunderDuration(Integer.MAX_VALUE);
        } else {
            w.setStorm(false);
            w.setThundering(false);
            w.setClearWeatherDuration(20 * 60 * 10);
        }
    }

    private void applyNight(boolean enable) {
        World w = targetWorld();
        if (w == null) return;

        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, !enable);

        if (enable) {
            w.setTime(18000L);
        }
    }

    private void applyFog(boolean enable) {
        if (enable) {
            startFogTask();
        } else {
            stopFogTask();
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.removePotionEffect(PotionEffectType.WEAKNESS);
            }
        }
    }

    private void startFogTask() {
        stopFogTask();

        int refreshTicks = getConfig().getInt("effects.fog.refresh-ticks", 40);
        boolean useWeakness = getConfig().getBoolean("effects.fog.use-weakness", true);

        fogTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("effects.fog.enabled")) return;

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (useWeakness) {
                    p.addPotionEffect(new PotionEffect(
                            PotionEffectType.WEAKNESS,
                            refreshTicks + 20,
                            0,
                            true,
                            false,
                            false
                    ));
                }
            }
        }, 0L, refreshTicks);
    }

    private void stopFogTask() {
        if (fogTaskId != -1) {
            Bukkit.getScheduler().cancelTask(fogTaskId);
            fogTaskId = -1;
        }
    }
}
