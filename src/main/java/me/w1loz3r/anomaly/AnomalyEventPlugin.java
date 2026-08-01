package me.w1loz3r.anomaly;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
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

        getLogger().info("AnomalyEvent v2.5 enabled.");
    }

    @Override
    public void onDisable() {
        stopTasks();
        clearPlayerEffects();
        saveRiftState();
        getLogger().info("AnomalyEvent v2.5 disabled.");
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
                sender.sendMessage("§7fog-level: §f" + getConfig().getInt("effects.fog.level", 1));
                sender.sendMessage("§7rift-built: §f" + riftBuilt);
                sender.sendMessage("§7saved-blocks: §f" + changedBlocks.size());
                sender.sendMessage("§7rift.length: §f" + getConfig().getInt("rift.length", 22));
                sender.sendMessage("§7rift.half-width: §f" + getConfig().getInt("rift.half-width", 2));
                sender.sendMessage("§7rift.bottom-half-width: §f" + getConfig().getInt("rift.bottom-half-width", 1));
                sender.sendMessage("§7rift.shell-thickness: §f" + getConfig().getInt("rift.shell-thickness", 2));
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
                        sender.sendMessage("§e/anomaly rift size <length> <halfWidthTop> <jagged>");
                        sender.sendMessage("§7(jagged оставлен для совместимости, фактически не влияет в v2.5)");
                        return true;
                    }

                    Integer length = tryParseInt(args[2]);
                    Integer halfWidthTop = tryParseInt(args[3]);
                    Integer jaggedCompat = tryParseInt(args[4]);

                    if (length == null || halfWidthTop == null || jaggedCompat == null) {
                        sender.sendMessage("§cЧисла введены неверно.");
                        return true;
                    }

                    if (length < 10 || length > 350 || halfWidthTop < 1 || halfWidthTop > 40 || jaggedCompat < 0 || jaggedCompat > 20) {
                        sender.sendMessage("§cОграничения: length 10-350, halfWidthTop 1-40, jagged 0-20");
                        return true;
                    }

                    getConfig().set("rift.length", length);
                    getConfig().set("rift.half-width", halfWidthTop);
                    getConfig().set("rift.jagged", jaggedCompat); // совместимость
                    // по умолчанию низ делаем уже
                    int bottom = Math.max(1, halfWidthTop / 3);
                    getConfig().set("rift.bottom-half-width", bottom);

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

        boolean inZ = Math.abs(to.getBlockZ() - cz) <= (length / 2 + 2);
        boolean inX = Math.abs(to.getBlockX() - cx) <= (halfWidth + 2);

        int teleportY = getConfig().getInt("safety.teleport-min-y", 15);
        if (inZ && inX && to.getY() <= teleportY) {
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

        if (isProtectedRiftZone(e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cВ зоне разлома нельзя ломать блоки.");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!getConfig().getBoolean("state.anomaly-enabled", false)) return;
        if (riftCenter == null) return;

        if (isProtectedRiftZone(e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cВ зоне разлома нельзя ставить блоки.");
        }
    }

    private boolean isProtectedRiftZone(Location loc) {
        if (riftCenter == null || loc == null) return false;
        if (!loc.getWorld().equals(riftCenter.getWorld())) return false;

        int cx = riftCenter.getBlockX();
        int cz = riftCenter.getBlockZ();

        int length = getConfig().getInt("rift.length", 22);
        int halfWidth = getConfig().getInt("rift.half-width", 2);
        int protectPad = getConfig().getInt("rift.protect-padding", 4);

        boolean inZ = Math.abs(loc.getBlockZ() - cz) <= (length / 2 + protectPad);
        boolean inX = Math.abs(loc.getBlockX() - cx) <= (halfWidth + 3 + protectPad);

        return inZ && inX;
    }

    private void help(CommandSender s) {
        s.sendMessage("§e/anomaly on");
        s.sendMessage("§e/anomaly off");
        s.sendMessage("§e/anomaly status");
        s.sendMessage("§e/anomaly fog <on|off|level 0-3>");
        s.sendMessage("§e/anomaly storm <on|off>");
        s.sendMessage("§e/anomaly night <on|off>");
        s.sendMessage("§e/anomaly rift size <length> <halfWidthTop> <jagged>");
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
        int x = spawn.getBlockX() + getConfig().getInt("safety.safe-offset-x", 8);
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

    /**
     * Стабильная детерминированная генерация:
     * - без случайного смещения оси (рамка не "плывет")
     * - плавное сужение к низу
     * - чистка блоков над разломом
     */
    private void buildRiftToBedrock(World w) {
        Location spawn = w.getSpawnLocation();
        int cx = spawn.getBlockX();
        int cz = spawn.getBlockZ();

        int minY = w.getMinHeight();
        int topY = w.getHighestBlockYAt(cx, cz) + 1;

        int length = getConfig().getInt("rift.length", 22);
        int halfWidthTop = getConfig().getInt("rift.half-width", 2);
        int halfWidthBottom = Math.max(1, getConfig().getInt("rift.bottom-half-width", 1));
        int shellThickness = getConfig().getInt("rift.shell-thickness", 2);

        riftCenter = new Location(w, cx + 0.5, topY, cz + 0.5);

        int height = Math.max(1, topY - minY);

        for (int dz = -length / 2; dz <= length / 2; dz++) {
            for (int y = topY; y >= minY; y--) {
                double t = (double) (topY - y) / (double) height; // 0..1
                int currentHalfWidth = (int) Math.round(halfWidthTop * (1.0 - t) + halfWidthBottom * t);

                // 1) Чистим внутренность разлома
                for (int dx = -currentHalfWidth; dx <= currentHalfWidth; dx++) {
                    int x = cx + dx;
                    int z = cz + dz;

                    Block b = w.getBlockAt(x, y, z);
                    rememberBlock(b);
                    b.setType(Material.AIR, false);
                }

                // 2) Чистим все блоки НАД разломом
                if (y == topY) {
                    for (int dx = -currentHalfWidth; dx <= currentHalfWidth; dx++) {
                        int x = cx + dx;
                        int z = cz + dz;
                        for (int ay = topY + 1; ay <= w.getMaxHeight() - 1; ay++) {
                            Block above = w.getBlockAt(x, ay, z);
                            if (above.getType() != Material.AIR) {
                                rememberBlock(above);
                                above.setType(Material.AIR, false);
                            }
                        }
                    }
                }

                // 3) Стабильная красивая рамка
                for (int s = 1; s <= shellThickness; s++) {
                    int leftX = cx - currentHalfWidth - s;
                    int rightX = cx + currentHalfWidth + s;

                    decorateEdgeStable(w.getBlockAt(leftX, y, cz + dz), y, minY, s);
                    decorateEdgeStable(w.getBlockAt(rightX, y, cz + dz), y, minY, s);
                }
            }
        }

        // Верхний обод по всей длине
        for (int dz = -length / 2; dz <= length / 2; dz++) {
            int leftX = cx - halfWidthTop - 1;
            int rightX = cx + halfWidthTop + 1;

            setIfNeeded(w.getBlockAt(leftX, topY, cz + dz), Material.CRYING_OBSIDIAN);
            setIfNeeded(w.getBlockAt(rightX, topY, cz + dz), Material.CRYING_OBSIDIAN);

            setIfNeeded(w.getBlockAt(leftX - 1, topY, cz + dz), Material.POLISHED_BLACKSTONE_BRICKS);
            setIfNeeded(w.getBlockAt(rightX + 1, topY, cz + dz), Material.POLISHED_BLACKSTONE_BRICKS);
        }

        Block core = w.getBlockAt(cx, topY - 1, cz);
        rememberBlock(core);
        core.setType(Material.RESPAWN_ANCHOR, false);

        w.playSound(riftCenter, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.7f);
        riftBuilt = true;
    }

    private void setIfNeeded(Block b, Material m) {
        rememberBlock(b);
        b.setType(m, false);
    }

    private void decorateEdgeStable(Block b, int y, int minY, int shellLayer) {
        Material mat;
        int depthFromBottom = y - minY;

        if (depthFromBottom <= 8) {
            mat = (shellLayer == 1) ? Material.CRYING_OBSIDIAN : Material.MAGMA_BLOCK;
        } else if (depthFromBottom <= 20) {
            mat = (shellLayer == 1) ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_TILES;
        } else {
            mat = (shellLayer == 1) ? Material.DEEPSLATE_BRICKS : Material.POLISHED_BLACKSTONE;
        }

        rememberBlock(b);
        b.setType(mat, false);
    }

    private void restoreRift(World w) {
        for (Map.Entry<String, Material> e : changedBlocks.entrySet()) {
            String[] p = e.getKey().split(":");
            if (p.length != 3) continue;

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
            int baseFogLevel = getConfig().getInt("effects.fog.level", 1);

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

                    double nearRadius = getConfig().getDouble("effects.fog.near-radius", 20.0);
                    double midRadius = getConfig().getDouble("effects.fog.mid-radius", 42.0);

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
            int hazardExtra = getConfig().getInt("hazard.extra-radius", 4);

            for (Player p : w.getPlayers()) {
                Location l = p.getLocation();

                boolean inZ = Math.abs(l.getBlockZ() - cz) <= (length / 2 + hazardExtra);
                boolean nearX = Math.abs(l.getBlockX() - cx) <= (halfWidth + 3 + hazardExtra);

                if (inZ && nearX) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 50, 0, true, true, true));
                    if (ThreadLocalRandom.current().nextInt(100) < 25) p.damage(1.0);
                }
            }
        }, 20L, period);
    }

    private void spawnRiftParticles(World w) {
        int length = getConfig().getInt("rift.length", 22);
        int halfWidth = getConfig().getInt("rift.half-width", 2);

        int cx = riftCenter.getBlockX();
        int cz = riftCenter.getBlockZ();
        int topY = w.getHighestBlockYAt(cx, cz);

        // адаптивно под размеры
        int step = (length >= 120) ? 1 : 2;
        int centerBursts = Math.max(12, halfWidth * 4);
        int sideBursts = Math.max(10, halfWidth * 3);

        for (int dz = -length / 2; dz <= length / 2; dz += step) {
            for (int i = 0; i < centerBursts; i++) {
                double px = cx + ThreadLocalRandom.current().nextDouble(-halfWidth * 0.7, halfWidth * 0.7);
                double pz = cz + dz + ThreadLocalRandom.current().nextDouble(-0.45, 0.45);
                double py = topY + ThreadLocalRandom.current().nextDouble(0.2, 2.8);

                w.spawnParticle(Particle.PORTAL, px, py, pz, 1, 0, 0, 0, 0.02);
                w.spawnParticle(Particle.SMOKE, px, py, pz, 1, 0, 0, 0, 0.001);
                w.spawnParticle(Particle.SOUL, px, py, pz, 1, 0, 0, 0, 0.001);
            }

            double leftX = cx - halfWidth - 0.5;
            double rightX = cx + halfWidth + 0.5;

            for (int i = 0; i < sideBursts; i++) {
                double py = topY + ThreadLocalRandom.current().nextDouble(0.1, 2.4);
                double pz = cz + dz + ThreadLocalRandom.current().nextDouble(-0.35, 0.35);

                w.spawnParticle(Particle.ENCHANT, leftX, py, pz, 1, 0, 0, 0, 0.01);
                w.spawnParticle(Particle.ENCHANT, rightX, py, pz, 1, 0, 0, 0, 0.01);

                if (ThreadLocalRandom.current().nextInt(100) < 35) {
                    w.spawnParticle(Particle.REVERSE_PORTAL, leftX, py, pz, 1, 0, 0, 0, 0.02);
                    w.spawnParticle(Particle.REVERSE_PORTAL, rightX, py, pz, 1, 0, 0, 0, 0.02);
                }
            }
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
        dataStore.save(
                changedBlocks,
                riftBuilt,
                w.getName(),
                riftCenter.getBlockX(),
                riftCenter.getBlockY(),
                riftCenter.getBlockZ()
        );
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
        addDefault("world", "world");

        addDefault("state.anomaly-enabled", false);
        addDefault("state.storm-enabled", true);
        addDefault("state.night-enabled", true);

        addDefault("rift.length", 22);
        addDefault("rift.half-width", 2);
        addDefault("rift.bottom-half-width", 1);
        addDefault("rift.jagged", 2); // только совместимость старых конфигов
        addDefault("rift.shell-thickness", 2);
        addDefault("rift.protect-padding", 4);

        addDefault("effects.refresh-ticks", 30);
        addDefault("effects.fog.enabled", true);
        addDefault("effects.fog.level", 1);
        addDefault("effects.fog.near-radius", 20.0);
        addDefault("effects.fog.mid-radius", 42.0);

        addDefault("hazard.period-ticks", 40);
        addDefault("hazard.extra-radius", 4);

        addDefault("safety.teleport-min-y", 15);
        addDefault("safety.safe-offset-x", 8);
        addDefault("safety.safe-offset-z", 0);

        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void addDefault(String path, Object value) {
        if (!getConfig().isSet(path)) getConfig().set(path, value);
    }
}
