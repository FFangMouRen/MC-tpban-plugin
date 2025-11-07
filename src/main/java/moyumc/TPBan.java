package moyumc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class TPBan extends JavaPlugin implements Listener, TabCompleter {
    private FileConfiguration config;
    private File configFile;
    private Map<UUID, BanData> bannedPlayers = new HashMap<>();
    private Map<UUID, Integer> taskIds = new HashMap<>();
    private Map<UUID, Location> lastMovePositions = new HashMap<>();
    private Map<UUID, Long> lastMoveTimes = new HashMap<>();

    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        this.configFile = new File(getDataFolder(), "config.yml");
        if (!this.configFile.exists()) {
            try {
                this.configFile.createNewFile();
                this.config = YamlConfiguration.loadConfiguration(this.configFile);
                this.config.set("maxXOffset", 10);
                this.config.set("maxYOffset", 10);
                this.config.set("maxZOffset", 10);
                World world = Bukkit.getWorlds().get(0);
                Location spawn = world.getSpawnLocation();
                this.config.set("banLocation.x", spawn.getX());
                this.config.set("banLocation.y", spawn.getY());
                this.config.set("banLocation.z", spawn.getZ());
                this.config.set("banLocation.world", world.getName());
                this.config.save(this.configFile);
            } catch (IOException e) {
                getLogger().severe("无法创建配置文件！");
                e.printStackTrace();
            }
        } else {
            this.config = YamlConfiguration.loadConfiguration(this.configFile);
        }
        loadBannedPlayers();
        getServer().getPluginManager().registerEvents(this, this);
        startBanChecks();
    }

    public void onDisable() {
        saveBannedPlayers();
    }

    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("tpban")) {
            if (args.length == 1) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                        completions.add(player.getName());
                    }
                }
                if ("clear".startsWith(args[0].toLowerCase())) {
                    completions.add("clear");
                }
            } else if (args.length == 2 && !args[0].equalsIgnoreCase("clear")) {
                completions.add("10分钟");
                completions.add("30分钟");
                completions.add("1小时");
                completions.add("2小时");
                completions.add("1天");
            } else if (args.length >= 3 && !args[0].equalsIgnoreCase("clear")) {
                completions.add("作弊");
                completions.add("不当行为");
                completions.add("辱骂");
                completions.add("破坏");
                completions.add("其他违规");
            }
        }
        return completions;
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("tpban")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.RED + "用法: /tpban <玩家|clear> [时间] [原因]");
                return true;
            } else if (args[0].equalsIgnoreCase("clear")) {
                if (!sender.hasPermission("tpban.admin")) {
                    sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
                    return true;
                } else if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "用法: /tpban clear <玩家>");
                    return true;
                } else {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(ChatColor.RED + "玩家未找到！");
                        return true;
                    } else if (this.bannedPlayers.containsKey(target.getUniqueId())) {
                        BanData data = this.bannedPlayers.get(target.getUniqueId());
                        target.teleport(data.getOriginalLocation());
                        cancelPlayerTasks(target);
                        lastMovePositions.remove(target.getUniqueId());
                        lastMoveTimes.remove(target.getUniqueId());
                        this.bannedPlayers.remove(target.getUniqueId());
                        saveBannedPlayers();
                        target.sendMessage(ChatColor.GREEN + "你的封禁已被解除！");
                        sender.sendMessage(ChatColor.GREEN + "已解除玩家 " + target.getName() + " 的封禁状态！");
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + "该玩家未被封禁！");
                        return true;
                    }
                }
            } else if (!sender.hasPermission("tpban.command")) {
                sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
                return true;
            } else if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "用法: /tpban <玩家> <时间> <原因>");
                return true;
            } else {
                Player target2 = Bukkit.getPlayer(args[0]);
                if (target2 == null) {
                    sender.sendMessage(ChatColor.RED + "玩家未找到！");
                    return true;
                }
                try {
                    long time = parseTime(args[1]);
                    StringBuilder reasonBuilder = new StringBuilder();
                    for (int i = 2; i < args.length; i++) {
                        reasonBuilder.append(args[i]).append(" ");
                    }
                    String reason = reasonBuilder.toString().trim();
                    banPlayer(target2, time, reason);
                    sender.sendMessage(ChatColor.RED + "玩家 " + target2.getName() + " 已被TP封禁 " + args[1] + " 原因: " + reason);
                    return true;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "无效的时间格式！使用类似 1小时30分钟, 30分钟, 2小时 等格式。");
                    return true;
                }
            }
        } else if (cmd.getName().equalsIgnoreCase("tpban_setxyz")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家可以使用此命令！");
                return true;
            } else if (!sender.hasPermission("tpban.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限使用此命令！");
                return true;
            } else {
                Player player = (Player) sender;
                Location loc = player.getLocation();
                this.config.set("banLocation.x", loc.getX());
                this.config.set("banLocation.y", loc.getY());
                this.config.set("banLocation.z", loc.getZ());
                this.config.set("banLocation.world", loc.getWorld().getName());
                try {
                    this.config.save(this.configFile);
                    sender.sendMessage(ChatColor.GREEN + "TP封禁位置已设置为当前位置！");
                    return true;
                } catch (IOException e2) {
                    sender.sendMessage(ChatColor.RED + "保存封禁位置失败！");
                    e2.printStackTrace();
                    return true;
                }
            }
        } else {
            return false;
        }
    }

    private long parseTime(String timeStr) throws NumberFormatException {
        long total = 0;
        String numStr = "";
        for (char c : timeStr.toCharArray()) {
            if (Character.isDigit(c)) {
                numStr += c;
            } else if (!numStr.isEmpty()) {
                long num = Long.parseLong(numStr);
                numStr = "";
                switch (c) {
                    case '分': // 分钟
                    case 'm': // 兼容字母格式
                        total += num * 60;
                        break;
                    case '时': // 小时
                    case 'h': // 兼容字母格式
                        total += num * 3600;
                        break;
                    case '天': // 天
                    case 'd': // 兼容字母格式
                        total += num * 86400;
                        break;
                    default:
                        throw new NumberFormatException("无效时间单位: " + c);
                }
            }
        }
        if (!numStr.isEmpty()) {
            total += Long.parseLong(numStr) * 60; // 默认单位是分钟
        }
        return total * 1000; // 转换为毫秒
    }

    private void banPlayer(Player player, long duration, String reason) {
        Location originalLoc = player.getLocation();
        Location banLoc = getBanLocation();
        BanData data = new BanData(System.currentTimeMillis(), System.currentTimeMillis() + duration, originalLoc, reason);
        this.bannedPlayers.put(player.getUniqueId(), data);

        // 初始化移动检测
        lastMovePositions.put(player.getUniqueId(), banLoc.clone());
        lastMoveTimes.put(player.getUniqueId(), System.currentTimeMillis());

        saveBannedPlayers();
        player.teleport(banLoc);
        startPlayerBanTasks(player);
    }

    private Location getBanLocation() {
        if (!this.config.contains("banLocation.world")) {
            World world = Bukkit.getWorlds().get(0);
            Location spawn = world.getSpawnLocation();
            this.config.set("banLocation.x", spawn.getX());
            this.config.set("banLocation.y", spawn.getY());
            this.config.set("banLocation.z", spawn.getZ());
            this.config.set("banLocation.world", world.getName());
            try {
                this.config.save(this.configFile);
            } catch (IOException e) {
                getLogger().warning("无法保存默认封禁位置！");
            }
            return spawn;
        }
        double x = this.config.getDouble("banLocation.x");
        double y = this.config.getDouble("banLocation.y");
        double z = this.config.getDouble("banLocation.z");
        String worldName = this.config.getString("banLocation.world");
        World world2 = Bukkit.getWorld(worldName);
        if (world2 == null) {
            world2 = Bukkit.getWorlds().get(0);
            this.config.set("banLocation.world", world2.getName());
            try {
                this.config.save(this.configFile);
            } catch (IOException e2) {
                getLogger().warning("无法更新封禁世界！");
            }
        }
        return new Location(world2, x, y, z);
    }

    private void startPlayerBanTasks(Player player) {
        cancelPlayerTasks(player);
        UUID uuid = player.getUniqueId();

        // 位置检测任务（每秒检查）
        int positionTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                // 检查玩家是否在封禁区域内
                Location banLoc = getBanLocation();
                Location playerLoc = player.getLocation();

                double maxXOffset = config.getInt("maxXOffset", 10);
                double maxYOffset = config.getInt("maxYOffset", 10);
                double maxZOffset = config.getInt("maxZOffset", 10);

                boolean inArea = Math.abs(playerLoc.getX() - banLoc.getX()) <= maxXOffset &&
                        Math.abs(playerLoc.getY() - banLoc.getY()) <= maxYOffset &&
                        Math.abs(playerLoc.getZ() - banLoc.getZ()) <= maxZOffset;

                if (!inArea) {
                    player.teleport(banLoc);
                }
            }
        }.runTaskTimer(this, 0L, 20L).getTaskId();

        // 聊天栏提醒任务（每5分钟）
        int chatReminderTaskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                BanData data = bannedPlayers.get(player.getUniqueId());
                if (data != null) {
                    long remaining = data.getEndTime() - System.currentTimeMillis();
                    player.sendMessage(ChatColor.RED + "你已被TP封禁 | 剩余时间: " + formatTime(remaining));
                    player.sendMessage(ChatColor.RED + "原因: " + data.getReason());
                }
            }
        }.runTaskTimer(this, 6000L, 6000L).getTaskId(); // 5分钟 = 6000 ticks

        taskIds.put(uuid, positionTaskId);
        taskIds.put(uuid, chatReminderTaskId);
    }

    private void cancelPlayerTasks(Player player) {
        UUID uuid = player.getUniqueId();
        List<Integer> ids = new ArrayList<>();
        while (taskIds.containsKey(uuid)) {
            ids.add(taskIds.remove(uuid));
        }
        for (Integer id : ids) {
            if (id != null) {
                Bukkit.getScheduler().cancelTask(id);
            }
        }
    }

    private void startBanChecks() {
        // 无操作检测任务（每分钟检查）
        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                for (Map.Entry<UUID, BanData> entry : new HashMap<>(bannedPlayers).entrySet()) {
                    UUID uuid = entry.getKey();
                    Player player = Bukkit.getPlayer(uuid);

                    if (player != null && player.isOnline()) {
                        Long lastMoveTime = lastMoveTimes.get(uuid);
                        // 超过2分钟无操作
                        if (lastMoveTime != null && (currentTime - lastMoveTime) > 120000) {
                            player.kickPlayer(ChatColor.RED + "由于长时间无操作，你已被踢出服务器");
                            getLogger().info("踢出无操作玩家: " + player.getName());
                        }
                    }
                }
            }
        }.runTaskTimer(this, 1200L, 1200L); // 每分钟检查（1200 ticks = 60秒）
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (this.bannedPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (this.bannedPlayers.containsKey(uuid)) {
            BanData data = this.bannedPlayers.get(uuid);
            if (System.currentTimeMillis() >= data.getEndTime()) {
                this.bannedPlayers.remove(uuid);
                saveBannedPlayers();
                return;
            }
            Location banLoc = getBanLocation();
            player.teleport(banLoc);

            // 初始化移动检测
            lastMovePositions.put(uuid, banLoc.clone());
            lastMoveTimes.put(uuid, System.currentTimeMillis());

            startPlayerBanTasks(player);
            player.sendMessage(ChatColor.RED + "你的封禁仍在继续，剩余时间: " + formatTime(data.getEndTime() - System.currentTimeMillis()));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (this.bannedPlayers.containsKey(uuid)) {
            cancelPlayerTasks(player);
            lastMovePositions.remove(uuid);
            lastMoveTimes.remove(uuid);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (this.bannedPlayers.containsKey(player.getUniqueId())) {
            event.setRespawnLocation(getBanLocation());
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (bannedPlayers.containsKey(uuid)) {
            // 只记录实际位置变化（忽略头部转动）
            if (event.getFrom().getX() != event.getTo().getX() ||
                    event.getFrom().getY() != event.getTo().getY() ||
                    event.getFrom().getZ() != event.getTo().getZ()) {

                lastMovePositions.put(uuid, event.getTo());
                lastMoveTimes.put(uuid, System.currentTimeMillis());
            }
        }
    }

    private String formatTime(long millis) {
        millis = Math.max(0, millis);
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        seconds %= 60;
        minutes %= 60;
        hours %= 24;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天 ");
        }
        if (hours > 0) {
            sb.append(hours).append("小时 ");
        }
        if (minutes > 0) {
            sb.append(minutes).append("分钟 ");
        }
        sb.append(seconds).append("秒");
        return sb.toString();
    }

    private void saveBannedPlayers() {
        File dataFile = new File(getDataFolder(), "banned_players.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, BanData> entry : this.bannedPlayers.entrySet()) {
            String path = "players." + entry.getKey().toString();
            BanData data = entry.getValue();
            yaml.set(path + ".startTime", data.getStartTime());
            yaml.set(path + ".endTime", data.getEndTime());
            yaml.set(path + ".reason", data.getReason());
            Location loc = data.getOriginalLocation();
            yaml.set(path + ".originalLocation.x", loc.getX());
            yaml.set(path + ".originalLocation.y", loc.getY());
            yaml.set(path + ".originalLocation.z", loc.getZ());
            yaml.set(path + ".originalLocation.world", loc.getWorld().getName());
        }
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("保存封禁玩家数据失败！");
            e.printStackTrace();
        }
    }

    private void loadBannedPlayers() {
        File dataFile = new File(getDataFolder(), "banned_players.yml");
        if (dataFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
            if (yaml.contains("players")) {
                for (String key : yaml.getConfigurationSection("players").getKeys(false)) {
                    UUID uuid = UUID.fromString(key);
                    String path = "players." + key;
                    long startTime = yaml.getLong(path + ".startTime");
                    long endTime = yaml.getLong(path + ".endTime");
                    String reason = yaml.getString(path + ".reason");
                    double x = yaml.getDouble(path + ".originalLocation.x");
                    double y = yaml.getDouble(path + ".originalLocation.y");
                    double z = yaml.getDouble(path + ".originalLocation.z");
                    String worldName = yaml.getString(path + ".originalLocation.world");
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        world = Bukkit.getWorlds().get(0);
                    }
                    Location originalLoc = new Location(world, x, y, z);
                    BanData data = new BanData(startTime, endTime, originalLoc, reason);
                    this.bannedPlayers.put(uuid, data);
                }
            }
        }
    }

    // 简化版BanData类
    static class BanData {
        private final long startTime;
        private long endTime;
        private final Location originalLocation;
        private final String reason;

        public BanData(long startTime, long endTime, Location originalLocation, String reason) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.originalLocation = originalLocation;
            this.reason = reason;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getEndTime() {
            return endTime;
        }

        public void setEndTime(long endTime) {
            this.endTime = endTime;
        }

        public Location getOriginalLocation() {
            return originalLocation;
        }

        public String getReason() {
            return reason;
        }
    }
}