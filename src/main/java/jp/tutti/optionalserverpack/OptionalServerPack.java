package jp.tutti.optionalserverpack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OptionalServerPack - lets each player choose whether optional
 * server resource packs are applied to them.
 *
 * Design notes:
 * - Packs are pushed with Player#addResourcePack (the 1.20.3+ multi-pack
 *   API) with required=false, so the vanilla prompt lets the player
 *   decline, and other server packs (e.g. ItemsAdder's own pack, which
 *   ItemsAdder itself applies) are never touched.
 * - The player's choice (/serverpack on|off, or the client-side pack
 *   prompt result) is remembered per UUID and re-applied on later joins.
 * - Default is OFF: nothing is sent to a player until they opt in with
 *   /serverpack on. Only players who opted in get the packs re-sent on
 *   join (no join-time prompt for anyone else).
 */
public final class OptionalServerPack extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = "§b[OptionalServerPack] §r";

    /** One configured resource pack. */
    private static final class PackDef {
        final String name;
        final UUID uuid;       // stable across restarts (derived from name)
        final String url;      // URL handed to clients
        final byte[] sha1;     // raw SHA-1 bytes (may be null for url packs without a hash)

        PackDef(String name, String url, byte[] sha1) {
            this.name = name;
            // NOTE: the "modelpacktoggle:" seed predates the rename to
            // OptionalServerPack; keeping it preserves the pack UUIDs, so
            // clients keep their cached packs and consent across the rename.
            this.uuid = UUID.nameUUIDFromBytes(("modelpacktoggle:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.url = url;
            this.sha1 = sha1;
        }
    }

    private final List<PackDef> packs = new ArrayList<>();
    private final Map<UUID, PackDef> packsById = new LinkedHashMap<>();
    private final Map<UUID, Boolean> preferences = new LinkedHashMap<>();
    private File prefsFile;
    private PackServer packServer;

    private long joinDelayTicks = 60L;
    private String promptText = "";

    // PlaceholderAPI texts for %optionalserverpack_status%
    private String placeholderOn = "ON";
    private String placeholderOff = "OFF";
    private String placeholderNone = "未選択";

    // Player-facing messages (config: messages.*). Empty = send nothing.
    private String messageOn = "リソースパックをONにしました。";
    private String messageOff = "リソースパックをOFFにしました。";
    private String messageDeclined = "リソースパックをOFFにしました。有効化するには &e/serverpack on&r を実行してください。";

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        prefsFile = new File(getDataFolder(), "preferences.yml");
        loadPreferences();
        loadPacks();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("optionalserverpack") != null) {
            getCommand("optionalserverpack").setExecutor(this);
            getCommand("optionalserverpack").setTabCompleter(this);
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new OptionalServerPackPlaceholder(this).register();
            getLogger().info("PlaceholderAPI 連携を有効化しました（%optionalserverpack_status% など）。");
        }
        getLogger().info("OptionalServerPack enabled. packs=" + packs.size());
    }

    @Override
    public void onDisable() {
        stopPackServer();
        savePreferences();
    }

    // ------------------------------------------------------------------
    // Legacy data migration (the plugin was renamed from ModelPackToggle)
    // ------------------------------------------------------------------

    /**
     * This plugin was formerly called ModelPackToggle (the 1.x series),
     * so its data
     * folder was plugins/ModelPackToggle. If that folder exists and the
     * new folder has not been set up yet, copy config.yml,
     * preferences.yml and the packs folder across, so upgrading is just
     * a jar swap (plus removing the old jar).
     */
    private void migrateLegacyDataFolder() {
        File dataFolder = getDataFolder();
        if (new File(dataFolder, "config.yml").isFile()) return; // already set up
        File legacy = new File(dataFolder.getParentFile(), "ModelPackToggle");
        if (!legacy.isDirectory()) return;
        try {
            copyIfPresent(new File(legacy, "config.yml"), new File(dataFolder, "config.yml"));
            copyIfPresent(new File(legacy, "preferences.yml"), new File(dataFolder, "preferences.yml"));
            File legacyPacks = new File(legacy, "packs");
            if (legacyPacks.isDirectory()) {
                File[] files = legacyPacks.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile()) {
                            copyIfPresent(f, new File(new File(dataFolder, "packs"), f.getName()));
                        }
                    }
                }
            }
            getLogger().info("旧プラグイン（ModelPackToggle）のデータフォルダから設定を引き継ぎました。");
        } catch (IOException e) {
            getLogger().warning("旧データフォルダからの引き継ぎに失敗しました: " + e.getMessage());
        }
    }

    private static void copyIfPresent(File from, File to) throws IOException {
        if (!from.isFile() || to.isFile()) return;
        File parent = to.getParentFile();
        if (parent != null) parent.mkdirs();
        java.nio.file.Files.copy(from.toPath(), to.toPath());
    }

    // ------------------------------------------------------------------
    // Config / packs
    // ------------------------------------------------------------------

    private void loadPacks() {
        stopPackServer();
        packs.clear();
        packsById.clear();
        reloadConfig();
        FileConfiguration cfg = getConfig();
        joinDelayTicks = Math.max(0L, cfg.getLong("join-delay-ticks", 60L));
        promptText = cfg.getString("prompt", "");
        placeholderOn = cfg.getString("placeholder.on", "ON");
        placeholderOff = cfg.getString("placeholder.off", "OFF");
        placeholderNone = cfg.getString("placeholder.none", "未選択");
        messageOn = cfg.getString("messages.on", messageOn);
        messageOff = cfg.getString("messages.off", messageOff);
        messageDeclined = cfg.getString("messages.declined", messageDeclined);

        int webPort = cfg.getInt("web-port", 8173);
        String publicHost = cfg.getString("public-host", "");
        List<Map<?, ?>> rawPacks = cfg.getMapList("packs");

        // First pass: figure out whether the embedded web server is needed.
        boolean needsServer = false;
        for (Map<?, ?> raw : rawPacks) {
            if (raw.get("file") != null) { needsServer = true; break; }
        }
        if (needsServer) {
            if (publicHost == null || publicHost.isBlank()) {
                getLogger().warning("public-host が未設定です。プレイヤーがアクセスできるサーバーのホスト名/IP を config.yml に設定してください（このままでは localhost 扱いになります）。");
                publicHost = "localhost";
            }
            packServer = new PackServer(getLogger(), new File(getDataFolder(), "packs"), webPort);
            try {
                packServer.start();
            } catch (IOException e) {
                getLogger().severe("パック配信用Webサーバーの起動に失敗しました: " + e.getMessage());
                packServer = null;
            }
        }

        for (Map<?, ?> raw : rawPacks) {
            String name = String.valueOf(raw.get("name"));
            Object fileObj = raw.get("file");
            Object urlObj = raw.get("url");
            try {
                if (fileObj != null) {
                    String fileName = String.valueOf(fileObj);
                    File f = new File(new File(getDataFolder(), "packs"), fileName);
                    if (!f.isFile()) {
                        getLogger().warning("パックファイルが見つかりません: " + f.getPath() + " （このパックはスキップします）");
                        continue;
                    }
                    byte[] sha1 = sha1Of(f);
                    String url = "http://" + publicHost + ":" + webPort + "/" + fileName;
                    registerPack(new PackDef(name, url, sha1));
                } else if (urlObj != null) {
                    String url = String.valueOf(urlObj);
                    byte[] sha1 = null;
                    Object shaObj = raw.get("sha1");
                    if (shaObj != null) {
                        sha1 = hexToBytes(String.valueOf(shaObj).trim());
                    }
                    registerPack(new PackDef(name, url, sha1));
                } else {
                    getLogger().warning("パック '" + name + "' に file も url もありません（スキップします）。");
                }
            } catch (Exception e) {
                getLogger().warning("パック '" + name + "' の読み込みに失敗しました: " + e.getMessage());
            }
        }
        getLogger().info("パックを " + packs.size() + " 件読み込みました。");
    }

    private void registerPack(PackDef def) {
        packs.add(def);
        packsById.put(def.uuid, def);
        getLogger().info("  pack '" + def.name + "' -> " + def.url);
    }

    private void stopPackServer() {
        if (packServer != null) {
            packServer.stop();
            packServer = null;
        }
    }

    private static byte[] sha1Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Preferences (per-player ON/OFF, persisted)
    // ------------------------------------------------------------------

    private void loadPreferences() {
        preferences.clear();
        if (prefsFile == null || !prefsFile.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(prefsFile);
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                preferences.put(UUID.fromString(key), sec.getBoolean(key));
            } catch (IllegalArgumentException ignored) {
                // skip malformed keys
            }
        }
    }

    private void savePreferences() {
        if (prefsFile == null) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Boolean> e : preferences.entrySet()) {
            yml.set("players." + e.getKey(), e.getValue());
        }
        try {
            yml.save(prefsFile);
        } catch (IOException e) {
            getLogger().warning("preferences.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    /** @return TRUE=on, FALSE=off, null=not chosen yet */
    private Boolean preference(UUID playerId) {
        return preferences.get(playerId);
    }

    // ------------------------------------------------------------------
    // PlaceholderAPI support
    // ------------------------------------------------------------------

    /** @return TRUE=on, FALSE=off, null=not chosen yet */
    public Boolean getPreference(UUID playerId) {
        return preference(playerId);
    }

    /** Display text for %optionalserverpack_status% for the given player. */
    public String statusText(UUID playerId) {
        Boolean pref = preference(playerId);
        if (pref == null) return placeholderNone;
        return pref ? placeholderOn : placeholderOff;
    }

    private void setPreference(UUID playerId, boolean on) {
        preferences.put(playerId, on);
        savePreferences();
    }

    // ------------------------------------------------------------------
    // Pack push / remove
    // ------------------------------------------------------------------

    private void sendPacks(Player player) {
        String prompt = (promptText == null || promptText.isBlank()) ? null : promptText;
        for (PackDef def : packs) {
            // required=false: the player may decline; other plugins' packs
            // (ItemsAdder etc.) are separate entries and are not affected.
            player.addResourcePack(def.uuid, def.url, def.sha1, prompt, false);
        }
    }

    private void removePacks(Player player) {
        for (PackDef def : packs) {
            player.removeResourcePack(def.uuid);
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (packs.isEmpty()) return;
        // Default is OFF: only players who explicitly opted in (and have
        // not opted out since) get the packs re-sent. Everyone else is
        // left alone - no prompt on join.
        if (!Boolean.TRUE.equals(preference(player.getUniqueId()))) return;
        // Delay a little so we do not collide with other plugins' pack
        // handling right at join (ItemsAdder in particular).
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) sendPacks(player);
        }, joinDelayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        PackDef def = packsById.get(event.getID());
        if (def == null) return; // not one of ours
        Player player = event.getPlayer();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                if (!Boolean.TRUE.equals(preference(player.getUniqueId()))) {
                    setPreference(player.getUniqueId(), true);
                }
            }
            case DECLINED, DISCARDED -> {
                boolean wasOn = !Boolean.FALSE.equals(preference(player.getUniqueId()));
                if (wasOn) {
                    setPreference(player.getUniqueId(), false);
                    if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
                        sendConfigured(player, messageDeclined);
                    }
                }
            }
            default -> { /* ACCEPTED / DOWNLOADED / failures: nothing to record */ }
        }
    }

    // ------------------------------------------------------------------
    // /optionalserverpack command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return showStatus(sender, targetOf(sender, null));
        }
        switch (args[0].toLowerCase()) {
            case "on" -> { return applyChoice(sender, targetOf(sender, args.length > 1 ? args[1] : null), true); }
            case "off" -> { return applyChoice(sender, targetOf(sender, args.length > 1 ? args[1] : null), false); }
            case "toggle" -> {
                Player target = targetOf(sender, args.length > 1 ? args[1] : null);
                if (target == null) return true;
                boolean now = !Boolean.TRUE.equals(preference(target.getUniqueId()));
                return applyChoice(sender, target, now);
            }
            case "status" -> { return showStatus(sender, targetOf(sender, args.length > 1 ? args[1] : null)); }
            case "reload" -> {
                if (!sender.hasPermission("optionalserverpack.admin")) {
                    sender.sendMessage(PREFIX + "権限がありません。");
                    return true;
                }
                loadPreferences();
                loadPacks();
                sender.sendMessage(PREFIX + "設定を再読み込みしました（パック " + packs.size() + " 件）。");
                return true;
            }
            default -> {
                sender.sendMessage(PREFIX + "使い方: /serverpack [on|off|toggle|status]");
                return true;
            }
        }
    }

    /** Resolve the target player: self, or another player when an admin names one. */
    private Player targetOf(CommandSender sender, String nameArg) {
        if (nameArg != null) {
            if (!sender.hasPermission("optionalserverpack.admin")) {
                sender.sendMessage(PREFIX + "他のプレイヤーを指定するには管理者権限が必要です。");
                return null;
            }
            Player target = Bukkit.getPlayerExact(nameArg);
            if (target == null) {
                sender.sendMessage(PREFIX + "プレイヤーが見つかりません: " + nameArg);
            }
            return target;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage(PREFIX + "コンソールからはプレイヤー名を指定してください: /serverpack <on|off> <player>");
        return null;
    }

    private boolean applyChoice(CommandSender sender, Player target, boolean on) {
        if (target == null) return true;
        setPreference(target.getUniqueId(), on);
        if (on) {
            sendPacks(target);
        } else {
            removePacks(target);
        }
        sendConfigured(target, on ? messageOn : messageOff);
        if (!sender.equals(target)) {
            sender.sendMessage(PREFIX + target.getName() + " のリソースパックを" + (on ? "ON" : "OFF") + "にしました。");
        }
        return true;
    }

    /**
     * Send a config-defined message to a player: '&' colour codes are
     * translated, %player% is replaced with the player's name, and an
     * empty/blank text sends nothing at all.
     */
    private void sendConfigured(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        String text = ChatColor.translateAlternateColorCodes('&', raw.replace("%player%", player.getName()));
        player.sendMessage(PREFIX + text);
    }

    private boolean showStatus(CommandSender sender, Player target) {
        if (target == null) return true;
        Boolean pref = preference(target.getUniqueId());
        String state = pref == null ? "OFF（未選択＝デフォルト）" : (pref ? "ON" : "OFF");
        sender.sendMessage(PREFIX + target.getName() + " のリソースパック: " + state);
        if (sender.equals(target)) {
            sender.sendMessage(PREFIX + "切り替え: §e/serverpack on§r / §e/serverpack off");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("on", "off", "toggle", "status"));
            if (sender.hasPermission("optionalserverpack.admin")) base.add("reload");
            return base.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && sender.hasPermission("optionalserverpack.admin")
                && List.of("on", "off", "toggle", "status").contains(args[0].toLowerCase())) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
