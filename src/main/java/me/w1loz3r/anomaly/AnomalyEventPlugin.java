package me.w1loz3r.anomaly;

import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AnomalyEventPlugin extends JavaPlugin implements Listener {

    private int ambienceTaskId = -1;
    private int weatherLockTaskId = -1;
    private int hazardTaskId = -1;

    private final Map<String, Material> changedBlocks = new HashMap<>();
    private Location riftCenter;
    private boolean riftBuilt = false;

    private RiftDataStore dataStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyDefaultsIfMissing();
        dataStore = new RiftDataStore(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        loadRiftState();

        if (getConfig().getBoolean("state.anomaly-enabled", false)) {
            Bukkit.getScheduler().runTask(this, this::enableAnomaly);
        }

        getLogger().info("AnomalyEvent v2.2 enabled.");
    }

    @Override
    public void onDisable() {
        stopTasks();
        clearPlayerEffects();
        saveRiftState();
        getLogger().info("AnomalyEvent v2.2 disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anomaly.admin")) {
            sender.sendMessage("§cНет прав.");
            return true;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "on" -> {
                getConfig().set("state.anomaly-enabled", true);
                saveConfig();
                enableAnomaly();
                Bukkit.broadcastMessage("§5[Аномалия] §dРазлом открылся...");
                sender.sendMessage("§aАномалия включена.");
                return true;
            }

            case "off" -> {
                getConfig().set("state.anomaly-enabled", false);
                saveConfig();
                disableAnomaly();
                Bukkit.broadcastMessage("§5[Аномалия] §aРазлом закрыт.");
                sender.sendMessage("§aАномалия выключена.");
                return true;
            }

            case "status" -> {
                sender.sendMessage("§7=== §dAnomaly Status §7===");
                sender.sendMessage("§7enabled: §f" + getConfig().getBoolean("state.anomaly-enabled", false));
                sender.sendMessage("§7storm: §f" + getConfig().getBoolean("state.storm-enabled", true));
                sender.sendMessage("§7night: §f" + getConfig().getBoolean("state.night-enabled", true));
                sender.sendMessage("§7fog: §f" + getConfig().getBoolean("effects.fog.enabled", true));
                sender.sendMessage("§7fog-level: §f" + getConfig().getInt("effects.fog.level", 2));
                sender.sendMessage("§7rift-built: §f" + riftBuilt);
                sender.sendMessage("§7rift.length: §f" + getConfig().getInt("rift.length", 22));
                sender.sendMessage("§7rift.half-width: §f" + getConfig().getInt("rift.half-width", 2));
                sender.sendMessage("§7rift.jagged: §f" + getConfig().getInt("rift.jagged", 2));
                return true;
            }

            case "fog" -> {
                if (args.length < 2) {
                    sender.sendMessage("§e/anomaly fog <on|off|level 0-3>");
                    return true;
                }

                if (args[1].equalsIgnoreCase("on")) {
                    getConfig().set("effects.fog.enabled", true);
                    saveConfig();
                    sender.sendMessage("§aТуман включен.");
                    return true;
                }

                if (args[1].equalsIgnoreCase("off")) {
                    getConfig().set("effects.fog.enabled", false);
                    saveConfig();
                    clearFogOnly();
                    sender.sendMessage("§aТуман выключен.");
                    return true;
                }

                if (args[1].equalsIgnoreCase("level")) {
                    if (args.length < 3) {
                        sender.sendMessage("§e/anomaly fog level <0-3>");
                        return true;
                    }
                    Integer lvl = tryParseInt(args[2]);
                    if (lvl == null || lvl < 0 || lvl > 3) {
                        sender.sendMessage("§cУровень должен быть от 0 до 3.");
                        return true;
                    }
                    getConfig().set("effects.fog.level", lvl);
                    getConfig().set("effects.fog.enabled", lvl > 0);
                    saveConfig();
                    sender.sendMessage("§aУровень тумана: " + lvl);
                    return true;
                }

                sender.sendMessage("§e/anomaly fog <on|off|level 0-3>");
                return true;
            }

            case "storm" -> {
                if (args.length < 2) {
                    sender.sendMessage("§e/anomaly storm <on|off>");
                    return true;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                getConfig().set("state.storm-enabled", on);
                saveConfig();

                World w = targetWorld();
                if (w != null) applyStorm(w, on);

                sender.sendMessage(on ? "§aГроза включена." : "§aГроза выключена.");
                return true;
            }

            case "night" -> {
                if (args.length < 2) {
                    sender.sendMessage("§e/anomaly night <on|off>");
                    return true;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                getConfig().set("state.night-enabled", on);
                saveConfig();

                World w = targetWorld();
                if (w != null) applyNight(w, on);

                sender.sendMessage(on ? "§aНочь зафиксирована." : "§aЦикл дня восстановлен.");
                return true;
            }

            case "rift" -> {
                if (args.length < 2) {
                    sender.sendMessage("§e/anomaly rift <size|rebuild>");
                    return true;
                }

                if (args[1].equalsIgnoreCase("size")) {
                    if (args.length < 5) {
                        sender.sendMessage("§e/anomaly rift size <length> <halfWidth> <jagged>");
                        return true;
                    }
                    Integer length = tryParseInt(args[2]);
                    Integer halfWidth = tryParseInt(args[3]);
                    Integer jagged = tryParseInt(args[4]);

                    if (length == null || halfWidth == null || jagged == null) {
                        sender.sendMessage("§cЧисла введены неверно.");
                        return true;
                    }

                    if (length < 10 || length > 300 || halfWidth < 1 || halfWidth > 30 || jagged < 0 || jagged > 12) {
                        sender.sendMessage("§cОграничения: length 10-300, halfWidth 1-30, jagged 0-12");
                        return true;
                    }

                    getConfig().set("rift.length", length);
                    getConfig().set("rift.half-width", halfWidth);
                    getConfig().set("rift.jagged", jagged);
                    saveConfig();

                    sender.sendMessage("§aНовый размер сохранен. Примени: §e/anomaly rift rebuild");
                    return true;
                }

                if (args[1].equalsIgnoreCase("rebuild")) {
                    World w = targetWorld();
                    if (w == null) {
                        sender.sendMessage("§cМир не найден.");
                        return true;
                    }

                    boolean wasEnabled = getConfig().getBoolean("state.anomaly-enabled", false);

                    if (riftBuilt) {
                        restoreRift(w);
                        dataStore.clear();
                    }

                    if (wasEnabled) {
                        buildRiftToBedrock(w);
                        saveRiftState();
                        sender.sendMessage("§aРазлом перестроен.");
                    } else {
                        sender.sendMessage("§aСтарый разлом очищен. Включи аномалию: /anomaly on");
                    }
                    return true;
                }

                sender.sendMessage("§e/anomaly rift <size|rebuild>");
                return true;
            }

            default -> {
                help(sender);
                return true;
            }
        }
    }

    @EventHandler
public void onMove(PlayerMoveEvent e) {
    if (!getConfig().getBoolean("state.anomaly-enabled", false)) return;
    if (riftCenter == null) return;

    Player p = e.getPlayer();
    Location to = e.getTo();
    if (to == null) return;

    World w = riftCenter.getWorld();
    if (w == null || !to.getWorld().equals(w)) return;

    int cx = riftCenter.getBlockX();
    int cz = riftCenter.getBlockZ();

    int length = getConfig().getInt("rift.length", 22);
    int halfWidth = getConfig().getInt("rift.half-width", 2);

    boolean inZ = Math.abs(to.getBlockZ() - cz) <= (length / 2 + 1);
    boolean inX = Math.abs(to.getBlockX() - cx) <= (halfWidth + 1);

    if (!(inZ && inX)) return;

    // Не телепортировать, если игрок еще сверху над разломом
    int riftTopY = riftCenter.getBlockY();
    if (to.getY() >= (riftTopY - 0.2)) return;

    int teleportY = getConfig().getInt("safety.teleport-min-y", 15);
    if (to.getY() <= teleportY) {
        Location safe = findSafeLocation(w);
        p.teleport(safe);
        p.playSound(safe, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
        p.sendMessage("§5[Аномалия] §dТебя выбросило из разлома.");
    }
}
@EventHandler
public void onBreak(BlockBreakEvent e) {
    if (!getConfig().getBoolean("state.anomaly-enabled", false)) return;
    if (riftCenter == null) return;
    if (!e.getBlock().getWorld().equals(riftCenter.getWorld())) return;

    if (e.getPlayer().isOp()) return;

    int cx = riftCenter.getBlockX();
    int cz = riftCenter.getBlockZ();

    int length = getConfig().getInt("rift.length", 22);
    int halfWidth = getConfig().getInt("rift.half-width", 2);
    int pad = getConfig().getInt("rift.protect-padding", 3);

    boolean inZ = Math.abs(e.getBlock().getZ() - cz) <= (length / 2 + pad);
    boolean inX = Math.abs(e.getBlock().getX() - cx) <= (halfWidth + 1 + pad);

    if (inZ && inX) {
        e.setCancelled(true);
        e.getPlayer().sendMessage("§cВ зоне разлома нельзя ломать блоки.");
    }
}
    private void help(CommandSender s) {
        s.sendMessage("§e/anomaly on");
        s.sendMessage("§e/anomaly off");
        s.sendMessage("§e/anomaly status");
        s.sendMessage("§e/anomaly fog <on|off|level 0-3>");
        s.sendMessage("§e/anomaly storm <on|off>");
        s.sendMessage("§e/anomaly night <on|off>");
        s.sendMessage("§e/anomaly rift size <length> <halfWidth> <jagged>");
        s.sendMessage("§e/anomaly rift rebuild");
    }

    private Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception ex) { return null; }
    }

    private void enableAnomaly() {
        World w = targetWorld();
        if (w == null) return;

        applyNight(w, getConfig().getBoolean("state.night-enabled", true));
        applyStorm(w, getConfig().getBoolean("state.storm-enabled", true));

        if (!riftBuilt) {
            buildRiftToBedrock(w);
            saveRiftState();
        }

        startAmbienceTask(w);
        startWeatherLockTask(w);
        startHazardTask(w);
    }

    private void disableAnomaly() {
        World w = targetWorld();
        if (w == null) return;

        stopTasks();
        clearPlayerEffects();

        applyNight(w, false);
        applyStorm(w, false);

        if (riftBuilt) {
            restoreRift(w);
            dataStore.clear();
        }
    }

    private World targetWorld() {
        String worldName = getConfig().getString("world", "world");
        World w = Bukkit.getWorld(worldName);
        if (w == null && !Bukkit.getWorlds().isEmpty()) w = Bukkit.getWorlds().get(0);
        return w;
    }

    private Location findSafeLocation(World w) {
        Location spawn = w.getSpawnLocation();
        int x = spawn.getBlockX() + getConfig().getInt("safety.safe-offset-x", 6);
        int z = spawn.getBlockZ() + getConfig().getInt("safety.safe-offset-z", 0);
        int y = w.getHighestBlockYAt(x, z) + 1;
        return new Location(w, x + 0.5, y, z + 0.5, spawn.getYaw(), spawn.getPitch());
    }

    private void applyStorm(World w, boolean enable) {
        if (enable) {
            w.setStorm(true);
            w.setThundering(true);
            w.setWeatherDuration(Integer.MAX_VALUE);
            w.setThunderDuration(Integer.MAX_VALUE);
        } else {
            w.setStorm(false);
            w.setThundering(false);
            w.setClearWeatherDuration(20 * 60 * 5);
        }
    }

    private void applyNight(World w, boolean enable) {
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, !enable);
        if (enable) w.setTime(18000L);
    }

private void buildRiftToBedrock(World w) {
    int cx;
    int cz;

    if (getConfig().contains("rift.center-x") && getConfig().contains("rift.center-z")) {
        cx = getConfig().getInt("rift.center-x");
        cz = getConfig().getInt("rift.center-z");
    } else if (riftCenter != null && riftCenter.getWorld().equals(w)) {
        cx = riftCenter.getBlockX();
        cz = riftCenter.getBlockZ();
    } else {
        Location sp = w.getSpawnLocation();
        cx = sp.getBlockX();
        cz = sp.getBlockZ();
    }

    int topY = getConfig().getInt("rift.top-y", w.getHighestBlockYAt(cx, cz));
    int length = getConfig().getInt("rift.length", 22);
    int halfWidth = getConfig().getInt("rift.half-width", 2);

    riftCenter = new Location(w, cx + 0.5, topY, cz + 0.5);
    int minY = w.getMinHeight() + 1;

    int maxOffset = Math.max(2, halfWidth + 2);
    int pathX = cx;
    int drift = 0;

    for (int dz = -length / 2; dz <= length / 2; dz++) {
        if (ThreadLocalRandom.current().nextInt(100) < 22) {
            drift += ThreadLocalRandom.current().nextInt(-1, 2);
            drift = Math.max(-1, Math.min(1, drift));
        }

        // Редкие "изломы"
        if (ThreadLocalRandom.current().nextInt(100) < 8) {
            pathX += ThreadLocalRandom.current().nextInt(-1, 2);
        }

        pathX += drift;
        pathX = Math.max(cx - maxOffset, Math.min(cx + maxOffset, pathX));

        int z = cz + dz;

        // Шире ядро
        int crackHalf = 1; // базово 3 блока
        if (ThreadLocalRandom.current().nextInt(100) < 35) crackHalf = 2; // часто 5
        if (ThreadLocalRandom.current().nextInt(100) < 10) crackHalf = 3; // иногда 7

        int cx1 = pathX - crackHalf;
        int cx2 = pathX + crackHalf;

        for (int x = cx1; x <= cx2; x++) {
            // Срезаем верхние слои гарантированно
            int startY = Math.max(topY + 3, w.getHighestBlockYAt(x, z) + 2);

            for (int y = startY; y >= minY; y--) {
                Block b = w.getBlockAt(x, y, z);
                Material m = b.getType();

                if (m == Material.BEDROCK) continue;

                if (m != Material.AIR) {
                    rememberBlock(b);
                    b.setType(Material.AIR, false);
                }
            }

            // Добивка "крышек" возле поверхности
            for (int y = topY + 4; y >= topY - 1; y--) {
                Block cap = w.getBlockAt(x, y, z);
                Material cm = cap.getType();
                if (cm == Material.SNOW
                        || cm == Material.SNOW_BLOCK
                        || cm == Material.TALL_GRASS
                        || cm == Material.SHORT_GRASS) {
                    rememberBlock(cap);
                    cap.setType(Material.AIR, false);
                }
            }
        }

        decorateEdge(w.getBlockAt(cx1 - 1, topY, z), topY, minY);
        decorateEdge(w.getBlockAt(cx2 + 1, topY, z), topY, minY);

        for (int y = topY - 6; y >= minY + 2; y -= 3) {
    decorateDepthEdge(w.getBlockAt(cx1 - 1, y, z), y, minY);
    decorateDepthEdge(w.getBlockAt(cx2 + 1, y, z), y, minY);
    }

    Block core = w.getBlockAt(cx, topY - 1, cz);
    rememberBlock(core);
    core.setType(Material.CRYING_OBSIDIAN, false);

    cleanupVegetationAroundRift(w, cx, cz, topY, length, halfWidth);
    w.playSound(riftCenter, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.7f);

    // Если метода decorateRiftCaps нет в классе, оставляем закомментированным:
    // decorateRiftCaps(w, cx, cz, topY, length, halfWidth);

    riftBuilt = true;
}
private void cleanupVegetationAroundRift(World w, int cx, int cz, int topY, int length, int halfWidth) {
    int sidePad = getConfig().getInt("rift.cleanup-side-pad", 4);
    int up = getConfig().getInt("rift.cleanup-up", 20);
    int down = getConfig().getInt("rift.cleanup-down", 2);

    int minX = cx - (halfWidth + sidePad);
    int maxX = cx + (halfWidth + sidePad);
    int minZ = cz - (length / 2 + 1);
    int maxZ = cz + (length / 2 + 1);

    int minY = Math.max(w.getMinHeight(), topY - down);
    int maxY = Math.min(w.getMaxHeight() - 1, topY + up);

    for (int x = minX; x <= maxX; x++) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                Block b = w.getBlockAt(x, y, z);
                Material m = b.getType();
                String n = m.name();

                boolean isVegetation =
        n.endsWith("_LOG") ||
        n.endsWith("_WOOD") ||
        n.endsWith("_LEAVES") ||
        n.equals("VINE") ||
        n.equals("CAVE_VINES") ||
        n.equals("CAVE_VINES_PLANT") ||
        n.equals("TWISTING_VINES") ||
        n.equals("TWISTING_VINES_PLANT") ||
        n.equals("WEEPING_VINES") ||
        n.equals("WEEPING_VINES_PLANT") ||
        n.equals("BAMBOO") ||
        n.equals("BAMBOO_SAPLING") ||
        n.equals("MANGROVE_ROOTS") ||
        n.equals("MUDDY_MANGROVE_ROOTS") ||
        n.equals("CHERRY_LEAVES") ||
        n.equals("SNOW") ||
        n.equals("SNOW_BLOCK");

                if (isVegetation) {
                    rememberBlock(b);
                    b.setType(Material.AIR, false);
                }
            }
        }
    }
}
    private void decorateDepthEdge(Block b, int y, int minY) {
    int roll = ThreadLocalRandom.current().nextInt(100);
    Material m;

    if (y <= minY + 12) {
        if (roll < 45) m = Material.BLACKSTONE;
        else if (roll < 75) m = Material.POLISHED_BLACKSTONE_BRICKS;
        else if (roll < 90) m = Material.SCULK;
        else m = Material.CRYING_OBSIDIAN;
    } else {
        if (roll < 35) m = Material.DEEPSLATE_BRICKS;
        else if (roll < 65) m = Material.POLISHED_BLACKSTONE;
        else if (roll < 85) m = Material.SCULK;
        else m = Material.CRYING_OBSIDIAN;
    }

    rememberBlock(b);
    b.setType(m, false);
}

private void spawnRiftParticles(World w) {
    if (riftCenter == null) return;

    int length = getConfig().getInt("rift.length", 22);
    int cx = riftCenter.getBlockX();
    int cz = riftCenter.getBlockZ();
    int y = riftCenter.getBlockY();

    for (int i = -length / 2; i <= length / 2; i++) {
        double px = cx + ThreadLocalRandom.current().nextDouble(-2.2, 2.2);
        double pz = cz + i + ThreadLocalRandom.current().nextDouble(-0.45, 0.45);
        double py = y + ThreadLocalRandom.current().nextDouble(-1.5, 3.0);

        w.spawnParticle(Particle.PORTAL, px, py, pz, 8, 0.25, 0.35, 0.25, 0.03);
        w.spawnParticle(Particle.SOUL, px, py, pz, 4, 0.2, 0.3, 0.2, 0.01);
        w.spawnParticle(Particle.SMOKE, px, py, pz, 5, 0.2, 0.25, 0.2, 0.002);

        if (ThreadLocalRandom.current().nextInt(100) < 12) {
            w.spawnParticle(Particle.SCULK_SOUL, px, py - 0.4, pz, 2, 0.1, 0.1, 0.1, 0.0);
        }
        if (ThreadLocalRandom.current().nextInt(100) < 8) {
            w.spawnParticle(Particle.REVERSE_PORTAL, px, py, pz, 3, 0.15, 0.2, 0.15, 0.02);
        }
    }

    if (ThreadLocalRandom.current().nextInt(100) < 10) {
        w.playSound(riftCenter, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 0.8f, 0.6f);
    }
    if (ThreadLocalRandom.current().nextInt(100) < 6) {
        w.playSound(riftCenter, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.5f, 0.7f);
    }
}

    private void restoreRift(World w) {
        for (Map.Entry<String, Material> e : changedBlocks.entrySet()) {
            String[] p = e.getKey().split(":");
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            w.getBlockAt(x, y, z).setType(e.getValue(), false);
        }

        changedBlocks.clear();
        riftBuilt = false;
        riftCenter = null;
    }

    private void startAmbienceTask(World w) {
        stopAmbienceTask();
        int period = getConfig().getInt("effects.refresh-ticks", 30);

        ambienceTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("state.anomaly-enabled", false)) return;

            boolean fogEnabled = getConfig().getBoolean("effects.fog.enabled", true);
            int baseFogLevel = getConfig().getInt("effects.fog.level", 2);

            int cx = (riftCenter != null) ? riftCenter.getBlockX() : 0;
            int cz = (riftCenter != null) ? riftCenter.getBlockZ() : 0;

            for (Player p : w.getPlayers()) {
                p.removePotionEffect(PotionEffectType.DARKNESS);
                p.removePotionEffect(PotionEffectType.SLOWNESS);

                if (!fogEnabled || baseFogLevel <= 0) continue;

                int finalFogLevel = baseFogLevel;

                if (riftCenter != null) {
                    double dx = p.getLocation().getX() - cx;
                    double dz = p.getLocation().getZ() - cz;
                    double dist = Math.sqrt(dx * dx + dz * dz);

                    double nearRadius = getConfig().getDouble("effects.fog.near-radius", 18.0);
                    double midRadius = getConfig().getDouble("effects.fog.mid-radius", 36.0);

                    if (dist <= nearRadius) finalFogLevel = 3;
                    else if (dist <= midRadius) finalFogLevel = Math.max(finalFogLevel, 2);
                }

                applyFogLevel(p, period, finalFogLevel);
            }

            if (riftCenter != null) spawnRiftParticles(w);
        }, 0L, period);
    }

    private void applyFogLevel(Player p, int period, int level) {
        if (level <= 0) return;

        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, period + 40, 0, true, false, false));

        if (level == 2) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, period + 40, 0, true, false, false));
        } else if (level >= 3) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, period + 40, 1, true, false, false));
        }
    }

    private void startHazardTask(World w) {
        stopHazardTask();
        int period = getConfig().getInt("hazard.period-ticks", 40);

        hazardTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("state.anomaly-enabled", false)) return;
            if (riftCenter == null) return;

            int cx = riftCenter.getBlockX();
            int cz = riftCenter.getBlockZ();

            int length = getConfig().getInt("rift.length", 22);
            int halfWidth = getConfig().getInt("rift.half-width", 2);
            int hazardExtra = getConfig().getInt("hazard.extra-radius", 3);

            for (Player p : w.getPlayers()) {
                Location l = p.getLocation();

                boolean inZ = Math.abs(l.getBlockZ() - cz) <= (length / 2 + hazardExtra);
                boolean nearX = Math.abs(l.getBlockX() - cx) <= (halfWidth + hazardExtra);

                if (inZ && nearX) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 50, 0, true, true, true));
                    if (ThreadLocalRandom.current().nextInt(100) < 20) p.damage(1.0);
                }
            }
        }, 20L, period);
    }

    private void spawnRiftParticles(World w) {
        int length = getConfig().getInt("rift.length", 22);
        int cx = riftCenter.getBlockX();
        int cz = riftCenter.getBlockZ();
        int y = w.getHighestBlockYAt(cx, cz);

        for (int i = -length / 2; i <= length / 2; i += 2) {
            double px = cx + ThreadLocalRandom.current().nextDouble(-1.8, 1.8);
            double pz = cz + i + ThreadLocalRandom.current().nextDouble(-0.4, 0.4);
            double py = y + ThreadLocalRandom.current().nextDouble(0.2, 2.2);

            w.spawnParticle(Particle.PORTAL, px, py, pz, 6, 0.2, 0.3, 0.2, 0.02);
            w.spawnParticle(Particle.SMOKE, px, py, pz, 4, 0.15, 0.2, 0.15, 0.001);
            w.spawnParticle(Particle.SOUL, px, py, pz, 2, 0.1, 0.2, 0.1, 0.001);
        }
    }

    private void startWeatherLockTask(World w) {
        stopWeatherLockTask();
        weatherLockTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("state.anomaly-enabled", false)) return;

            if (getConfig().getBoolean("state.storm-enabled", true)) {
                if (!w.hasStorm()) w.setStorm(true);
                if (!w.isThundering()) w.setThundering(true);
            }

            if (getConfig().getBoolean("state.night-enabled", true)) {
                w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                if (w.getTime() < 17000 || w.getTime() > 22000) w.setTime(18000L);
            }
        }, 0L, 200L);
    }

    private void stopTasks() {
        stopAmbienceTask();
        stopWeatherLockTask();
        stopHazardTask();
    }

    private void stopAmbienceTask() {
        if (ambienceTaskId != -1) {
            Bukkit.getScheduler().cancelTask(ambienceTaskId);
            ambienceTaskId = -1;
        }
    }

    private void stopWeatherLockTask() {
        if (weatherLockTaskId != -1) {
            Bukkit.getScheduler().cancelTask(weatherLockTaskId);
            weatherLockTaskId = -1;
        }
    }

    private void stopHazardTask() {
        if (hazardTaskId != -1) {
            Bukkit.getScheduler().cancelTask(hazardTaskId);
            hazardTaskId = -1;
        }
    }

    private void clearFogOnly() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.DARKNESS);
            p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    private void clearPlayerEffects() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.removePotionEffect(PotionEffectType.DARKNESS);
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.removePotionEffect(PotionEffectType.WITHER);
        }
    }

    private void rememberBlock(Block b) {
        String k = key(b.getX(), b.getY(), b.getZ());
        changedBlocks.putIfAbsent(k, b.getType());
    }

    private String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private void saveRiftState() {
        World w = targetWorld();
        if (w == null || riftCenter == null) return;
        dataStore.save(changedBlocks, riftBuilt, w.getName(), riftCenter.getBlockX(), riftCenter.getBlockY(), riftCenter.getBlockZ());
    }

    private void loadRiftState() {
        RiftDataStore.LoadedRiftState s = dataStore.load();
        if (!s.riftBuilt) return;

        changedBlocks.clear();
        changedBlocks.putAll(s.changedBlocks);
        riftBuilt = true;

        World w = Bukkit.getWorld(s.world);
        if (w == null && !Bukkit.getWorlds().isEmpty()) w = Bukkit.getWorlds().get(0);
        if (w != null) riftCenter = new Location(w, s.cx + 0.5, s.cy, s.cz + 0.5);
    }

    private void applyDefaultsIfMissing() {
        addDefault("rift.cleanup-side-pad", 10);
        addDefault("rift.cleanup-up", 20);
        addDefault("rift.cleanup-down", 2);
        addDefault("world", "world");

        addDefault("state.anomaly-enabled", false);
        addDefault("state.storm-enabled", true);
        addDefault("state.night-enabled", true);

        addDefault("rift.length", 22);
        addDefault("rift.half-width", 2);
        addDefault("rift.jagged", 2);

        addDefault("effects.refresh-ticks", 30);
        addDefault("effects.fog.enabled", true);
        addDefault("effects.fog.level", 2);
        addDefault("effects.fog.near-radius", 18.0);
        addDefault("effects.fog.mid-radius", 36.0);

        addDefault("hazard.period-ticks", 40);
        addDefault("hazard.extra-radius", 3);

        addDefault("safety.teleport-min-y", 15);
        addDefault("safety.safe-offset-x", 6);
        addDefault("safety.safe-offset-z", 0);

        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void addDefault(String path, Object value) {
        if (!getConfig().isSet(path)) getConfig().set(path, value);
    }
}
