package com.playcity.stats;

import com.playcity.stats.collect.CommandAliasConfig;
import com.playcity.stats.collect.CommandNormalizer;
import com.playcity.stats.collect.Keys;
import com.playcity.stats.collect.MaterialGroupClassifier;
import com.playcity.stats.collect.StatsAccumulator;
import com.playcity.stats.db.DatabaseManager;
import com.playcity.stats.db.DbType;
import com.playcity.stats.db.DurableStatsWriter;
import com.playcity.stats.db.SnapshotSpool;
import com.playcity.stats.db.StatsWriter;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class StatsPlugin extends JavaPlugin implements Listener {
    private static final int CONFIG_VERSION = 4;
    private static final PlainTextComponentSerializer CHAT_TEXT = PlainTextComponentSerializer.plainText();

    private final AtomicLong reloadSeq = new AtomicLong();
    private final Map<UUID, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<RuntimeContext> retiredContexts = new ConcurrentLinkedQueue<>();

    private volatile boolean active;
    private volatile boolean initializing;
    private volatile boolean stopping;
    private volatile RuntimeContext runtime;
    private volatile RuntimeContext initializingContext;
    private volatile Settings settings = Settings.defaults();

    private volatile Instant lastReloadAt;
    private volatile Instant lastFlushAttemptAt;
    private volatile Instant lastFlushSuccessAt;
    private volatile String lastFlushError;

    private ExecutorService ioExecutor;
    private BukkitTask tickTask;
    private BukkitTask flushTask;

    @Override
    public void onEnable() {
        stopping = false;
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Stats-IO");
            thread.setDaemon(false);
            return thread;
        });
        getServer().getPluginManager().registerEvents(this, this);
        requestReload(null);
    }

    @Override
    public void onDisable() {
        stopping = true;
        reloadSeq.incrementAndGet();
        active = false;
        initializing = false;
        cancelTasks();

        RuntimeContext current = runtime;
        runtime = null;
        if (current != null) {
            finalizeOnlineSessions(current, Instant.now());
            retireContext(current, false);
        }
        playerStates.clear();

        RuntimeContext candidate = initializingContext;
        initializingContext = null;

        ExecutorService executor = ioExecutor;
        if (executor != null) {
            try {
                if (candidate != null) {
                    executor.execute(candidate::close);
                }
                executor.execute(this::flushAndCloseAllContexts);
            } catch (RejectedExecutionException ignored) {
                if (candidate != null) {
                    candidate.close();
                }
                closeAllContextsWithoutFlush();
            }
            executor.shutdown();
        }
    }

    private void ensureResourceExists(String name) throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        var target = getDataFolder().toPath().resolve(name);
        if (Files.exists(target)) {
            return;
        }
        try (var source = getResource(name)) {
            if (source == null) {
                throw new IOException("Bundled resource is missing: " + name);
            }
            Files.copy(source, target);
        }
    }

    private void requestReload(CommandSender requester) {
        long seq = reloadSeq.incrementAndGet();
        initializing = true;
        lastReloadAt = Instant.now();

        if (requester != null) {
            requester.sendMessage("Stats: reload started.");
        }

        submitIo(() -> initializeReload(seq, requester));
    }

    private void initializeReload(long seq, CommandSender requester) {
        RuntimeContext candidate = null;
        try {
            ensureResourceExists("config.yml");
            ensureResourceExists("command-aliases.yml");
            YamlConfiguration config = loadConfigSnapshot();
            Settings loadedSettings = Settings.from(config);

            if (reloadSeq.get() != seq || stopping) {
                return;
            }

            if (!loadedSettings.setupEnabled()) {
                runOnMain(() -> applySafeMode(seq, loadedSettings, requester));
                return;
            }

            CommandNormalizer normalizer = new CommandNormalizer(
                CommandAliasConfig.load(new File(getDataFolder(), "command-aliases.yml"))
            );
            candidate = createRuntimeContext(seq, loadedSettings, config, normalizer);

            if (reloadSeq.get() != seq || stopping) {
                candidate.close();
                return;
            }

            initializingContext = candidate;
            if (reloadSeq.get() != seq || stopping) {
                if (initializingContext == candidate) {
                    initializingContext = null;
                }
                candidate.close();
                return;
            }

            RuntimeContext ready = candidate;
            runOnMain(() -> installRuntime(seq, ready, requester));
        } catch (Exception e) {
            if (candidate != null) {
                if (initializingContext == candidate) {
                    initializingContext = null;
                }
                candidate.close();
            }
            String error = describe(e);
            runOnMain(() -> handleReloadFailure(seq, requester, error));
        }
    }

    private RuntimeContext createRuntimeContext(
        long generation,
        Settings loadedSettings,
        FileConfiguration config,
        CommandNormalizer normalizer
    ) throws Exception {
        DatabaseManager databaseManager = new DatabaseManager(this, config);
        try {
            if (loadedSettings.autoCreateTables()) {
                databaseManager.ensureSchema(getPluginMeta().getVersion(), loadedSettings.autoMigrate());
            }
            StatsWriter writer = new StatsWriter(
                databaseManager,
                loadedSettings.maxBatchRows(),
                databaseManager.queryTimeoutSeconds()
            );
            DurableStatsWriter durableWriter = new DurableStatsWriter(
                writer,
                new SnapshotSpool(getDataFolder().toPath().resolve("spool"), databaseManager.storageIdentity())
            );
            DurableStatsWriter.RecoveryResult recovery = durableWriter.recover();
            if (recovery.snapshots() > 0) {
                getLogger().info("Recovered durable Stats batches: snapshots=" + recovery.snapshots()
                    + " rows=" + recovery.rows());
            }
            return new RuntimeContext(
                generation,
                loadedSettings,
                new StatsAccumulator(),
                normalizer,
                databaseManager,
                durableWriter
            );
        } catch (Exception e) {
            databaseManager.close();
            throw e;
        }
    }

    private YamlConfiguration loadConfigSnapshot() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File(getDataFolder(), "config.yml"));
        try (var defaultsStream = getResource("config.yml")) {
            if (defaultsStream != null) {
                YamlConfiguration defaults = new YamlConfiguration();
                try (var reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
                    defaults.load(reader);
                }
                config.setDefaults(defaults);
            }
        }
        return config;
    }

    private void applySafeMode(long seq, Settings loadedSettings, CommandSender requester) {
        if (reloadSeq.get() != seq || stopping) {
            return;
        }

        active = false;
        initializing = false;
        settings = loadedSettings;
        cancelTasks();

        RuntimeContext old = runtime;
        runtime = null;
        if (old != null) {
            finalizeOnlineSessions(old, Instant.now());
            retireContext(old, true);
        }
        playerStates.clear();

        getLogger().warning("Stats is in safe setup mode (setup.enabled=false).");
        if (requester != null) {
            requester.sendMessage("Stats: safe setup mode enabled; collection stopped.");
        }
    }

    private void installRuntime(long seq, RuntimeContext candidate, CommandSender requester) {
        if (initializingContext == candidate) {
            initializingContext = null;
        }
        if (reloadSeq.get() != seq || stopping || !isEnabled()) {
            submitIo(candidate::close);
            return;
        }

        active = false;
        cancelTasks();
        Instant now = Instant.now();

        RuntimeContext old = runtime;
        if (old != null) {
            finalizeOnlineSessions(old, now);
        }

        runtime = candidate;
        settings = candidate.settings;
        playerStates.clear();
        initializeOnlinePlayers(candidate, now);
        active = true;
        initializing = false;
        lastFlushError = null;
        startTasks(candidate);

        if (old != null) {
            retireContext(old, true);
        }

        getLogger().info("Stats enabled. DB=" + candidate.databaseManager.dbType().name().toLowerCase(Locale.ROOT)
            + " prefix=" + candidate.databaseManager.tablePrefix());
        if (requester != null) {
            requester.sendMessage("Stats: reload complete.");
        }
    }

    private void handleReloadFailure(long seq, CommandSender requester, String error) {
        if (reloadSeq.get() != seq || stopping) {
            return;
        }
        initializing = false;
        lastFlushError = error;
        getLogger().severe("Failed to reload Stats; keeping the previous runtime: " + error);
        if (requester != null) {
            requester.sendMessage("Stats: reload failed; previous runtime kept: " + error);
        }
    }

    private void startTasks(RuntimeContext context) {
        cancelTasks();
        long tickPeriod = context.settings.tickIntervalSeconds() * 20L;
        long flushPeriod = context.settings.flushIntervalSeconds() * 20L;
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> tickOnlinePlayers(context), 20L, tickPeriod);
        flushTask = getServer().getScheduler().runTaskTimer(this, () -> requestFlush(context, false, null), flushPeriod, flushPeriod);
    }

    private void cancelTasks() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
    }

    private void retireContext(RuntimeContext context, boolean submitFlush) {
        if (context.retired.compareAndSet(false, true)) {
            retiredContexts.add(context);
        }
        if (submitFlush) {
            submitIo(this::flushRetiredContexts);
        }
    }

    private void requestFlush(RuntimeContext context, boolean manual, CommandSender requester) {
        if (context == null || context.closed.get()) {
            if (manual && requester != null) {
                requester.sendMessage("Stats: inactive.");
            }
            return;
        }
        if (!context.flushQueued.compareAndSet(false, true)) {
            if (manual && requester != null) {
                requester.sendMessage("Stats: flush already queued or running.");
            }
            return;
        }

        submitIo(() -> {
            try {
                FlushOutcome outcome = flushContext(context, manual ? 2 : 1, context == runtime);
                if (manual && requester != null) {
                    sendMessageOnMain(requester, outcome.rows() == 0
                        ? "Stats: nothing to flush."
                        : "Stats: flush done (rows=" + outcome.rows() + ").");
                }
            } catch (Exception e) {
                String error = describe(e);
                if (context == runtime) {
                    lastFlushError = error;
                }
                getLogger().warning("Flush failed; batch retained for retry: " + error);
                if (manual && requester != null) {
                    sendMessageOnMain(requester, "Stats: flush failed; batch retained: " + error);
                }
            } finally {
                context.flushQueued.set(false);
            }
            flushRetiredContexts();
        });
    }

    private FlushOutcome flushContext(RuntimeContext context, int maxSnapshots, boolean recordStatus) throws Exception {
        int rows = 0;
        int snapshots = 0;
        while (snapshots < maxSnapshots) {
            StatsAccumulator.FlushSnapshot snapshot = context.pendingSnapshot;
            if (snapshot == null) {
                snapshot = context.accumulator.drainDeltas();
                if (snapshot.isEmpty()) {
                    break;
                }
                context.pendingSnapshot = snapshot;
                context.pendingRows = snapshot.rowCount();
            }

            if (recordStatus) {
                lastFlushAttemptAt = Instant.now();
            }
            context.writer.flush(snapshot);
            rows += snapshot.rowCount();
            snapshots++;
            context.pendingSnapshot = null;
            context.pendingRows = 0;
            context.accumulator.pruneActiveMinutesBefore(Keys.epochHour(Instant.now()));
            if (recordStatus) {
                lastFlushSuccessAt = Instant.now();
                lastFlushError = null;
            }
        }
        return new FlushOutcome(rows, snapshots);
    }

    private void flushRetiredContexts() {
        int count = retiredContexts.size();
        for (int i = 0; i < count; i++) {
            RuntimeContext context = retiredContexts.poll();
            if (context == null) {
                return;
            }
            try {
                flushContext(context, 2, false);
                if (context.pendingSnapshot == null
                    && !context.accumulator.hasPendingDeltas()
                    && context.inFlightAsyncCollectors.get() == 0) {
                    context.close();
                } else {
                    retiredContexts.add(context);
                }
            } catch (Exception e) {
                getLogger().warning("Retired runtime flush failed; retaining it for retry: " + describe(e));
                retiredContexts.add(context);
            }
        }
    }

    private void flushAndCloseAllContexts() {
        RuntimeContext context;
        while ((context = retiredContexts.poll()) != null) {
            try {
                awaitAsyncCollectors(context);
                flushContext(context, Integer.MAX_VALUE, false);
            } catch (Exception e) {
                if (context.writer.isPersisted(context.pendingSnapshot)) {
                    getLogger().warning("Final flush failed; durable batch retained for next startup: " + describe(e));
                } else {
                    getLogger().severe("Final flush and durable spool both failed; batch may be lost: " + describe(e));
                }
            } finally {
                context.close();
            }
        }
    }

    private void awaitAsyncCollectors(RuntimeContext context) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (context.inFlightAsyncCollectors.get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        if (context.inFlightAsyncCollectors.get() > 0) {
            getLogger().warning("Timed out waiting for async collectors during shutdown.");
        }
    }

    private void closeAllContextsWithoutFlush() {
        RuntimeContext context;
        while ((context = retiredContexts.poll()) != null) {
            context.close();
        }
    }

    private void submitIo(Runnable task) {
        ExecutorService executor = ioExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void runOnMain(Runnable task) {
        if (stopping || !isEnabled()) {
            return;
        }
        getServer().getScheduler().runTask(this, task);
    }

    private void sendMessageOnMain(CommandSender sender, String message) {
        runOnMain(() -> sender.sendMessage(message));
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission("stats.admin") || sender.isOp();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("Stats: /stats reload | /stats status | /stats db <ping|health> | /stats flush");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("stats")) {
            return false;
        }
        if (!hasAdminPermission(sender)) {
            sender.sendMessage("Stats: no permission.");
            return true;
        }
        if (!settings.commandsEnabled()) {
            sender.sendMessage("Stats: commands are disabled in config.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!settings.allowReload()) {
                sender.sendMessage("Stats: reload is disabled.");
            } else if (initializing) {
                sender.sendMessage("Stats: reload already in progress.");
            } else {
                requestReload(sender);
            }
            return true;
        }

        if (sub.equals("status")) {
            RuntimeContext context = runtime;
            sender.sendMessage("Stats: version=" + getPluginMeta().getVersion());
            sender.sendMessage("Stats: active=" + active + ", initializing=" + initializing);
            if (context != null) {
                sender.sendMessage("Stats: db.type=" + context.databaseManager.dbType().name().toLowerCase(Locale.ROOT)
                    + ", tablePrefix=" + context.databaseManager.tablePrefix());
                sender.sendMessage("Stats: pendingRows=" + context.pendingRows
                    + ", durablePendingBatches=" + context.writer.pendingFileCount()
                    + ", retiredRuntimes=" + retiredContexts.size());
            }
            if (lastReloadAt != null) sender.sendMessage("Stats: lastReloadAt=" + lastReloadAt);
            if (lastFlushAttemptAt != null) sender.sendMessage("Stats: lastFlushAttemptAt=" + lastFlushAttemptAt);
            if (lastFlushSuccessAt != null) sender.sendMessage("Stats: lastFlushSuccessAt=" + lastFlushSuccessAt);
            if (lastFlushError != null && !lastFlushError.isBlank()) sender.sendMessage("Stats: lastFlushError=" + lastFlushError);
            return true;
        }

        if (sub.equals("db")) {
            return handleDbCommand(sender, args);
        }

        if (sub.equals("flush")) {
            if (!settings.allowForceFlush()) {
                sender.sendMessage("Stats: flush is disabled.");
                return true;
            }
            RuntimeContext context = runtime;
            if (!active || context == null) {
                sender.sendMessage("Stats: inactive.");
                return true;
            }
            requestFlush(context, true, sender);
            return true;
        }

        sender.sendMessage("Stats: unknown subcommand.");
        return true;
    }

    private boolean handleDbCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Stats: /stats db <ping|health>");
            return true;
        }
        RuntimeContext context = runtime;
        if (initializing && context == null) {
            sender.sendMessage("Stats: initializing.");
            return true;
        }
        if (!active || context == null) {
            sender.sendMessage("Stats: inactive.");
            return true;
        }

        String dbSub = args[1].toLowerCase(Locale.ROOT);
        if (dbSub.equals("ping")) {
            if (!settings.allowDbPing()) {
                sender.sendMessage("Stats: db ping is disabled.");
                return true;
            }
            sender.sendMessage("Stats: db ping started.");
            submitIo(() -> {
                try {
                    long ms = context.databaseManager.pingMillis();
                    sendMessageOnMain(sender, "Stats: db ping OK (" + ms + " ms)");
                } catch (Exception e) {
                    sendMessageOnMain(sender, "Stats: db ping FAILED: " + describe(e));
                }
            });
            return true;
        }

        if (dbSub.equals("health")) {
            HikariPoolMXBean mx = context.databaseManager.dataSource().getHikariPoolMXBean();
            if (mx == null) {
                sender.sendMessage("Stats: pool stats unavailable.");
            } else {
                sender.sendMessage("Stats: pool active=" + mx.getActiveConnections()
                    + " idle=" + mx.getIdleConnections()
                    + " total=" + mx.getTotalConnections()
                    + " awaiting=" + mx.getThreadsAwaitingConnection());
            }
            return true;
        }

        sender.sendMessage("Stats: unknown db subcommand.");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        initializePlayer(context, event.getPlayer(), Instant.now(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();
        PlayerState state = playerStates.remove(uuid);
        if (state == null || state.generation != context.generation) {
            context.accumulator.upsertPlayer(uuid, now, now, player.getName());
            return;
        }

        accountPlayer(context, player, state, now);
        closeSession(context, uuid, player.getName(), state, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        RuntimeContext context = acquireAsyncContext();
        if (context == null) return;
        try {
            UUID uuid = event.getPlayer().getUniqueId();
            Instant now = Instant.now();
            int chars = CHAT_TEXT.serialize(event.originalMessage()).length();
            context.accumulator.addChat(uuid, now, chars);
            markActivity(context, uuid, now);
        } finally {
            releaseAsyncContext(context);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Instant now = Instant.now();
        markActivity(context, uuid, now);
        CommandNormalizer.NormalizedCommand normalized = context.commandNormalizer.normalize(event.getPlayer(), event.getMessage());
        if (normalized.commandKey().isBlank()) return;
        context.accumulator.addCommand(uuid, now, normalized.commandKey(), normalized.variantKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Instant now = Instant.now();
        Location from = event.getFrom();
        Location to = event.getTo();
        long meters = 0;
        if (to != null && from.getWorld() != null && to.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            meters = Math.round(from.distance(to));
        }
        context.accumulator.addTeleport(uuid, now, meters);
        markActivity(context, uuid, now);

        PlayerState state = stateFor(context, uuid);
        if (state != null && to != null) {
            state.currentWorld = to.getWorld() != null ? to.getWorld().getName() : state.currentWorld;
            state.lastX = to.getX();
            state.lastY = to.getY();
            state.lastZ = to.getZ();
            state.distanceRemainderM = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        RuntimeContext context = activeContext();
        if (context == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        Instant now = Instant.now();
        markActivity(context, uuid, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        Player player = event.getPlayer();
        PlayerState state = stateFor(context, player.getUniqueId());
        if (state != null) {
            Location location = player.getLocation();
            state.currentWorld = player.getWorld().getName();
            state.lastX = location.getX();
            state.lastY = location.getY();
            state.lastZ = location.getZ();
            state.distanceRemainderM = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Instant now = Instant.now();
        context.accumulator.addBlock(uuid, now, true, MaterialGroupClassifier.classify(event.getBlockPlaced().getType()));
        markActivity(context, uuid, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Instant now = Instant.now();
        context.accumulator.addBlock(uuid, now, false, MaterialGroupClassifier.classify(event.getBlock().getType()));
        markActivity(context, uuid, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        Player player = event.getEntity();
        String cause = player.getLastDamageCause() == null || player.getLastDamageCause().getCause() == null
            ? "unknown"
            : player.getLastDamageCause().getCause().name().toLowerCase(Locale.ROOT);
        context.accumulator.addDeath(player.getUniqueId(), Instant.now(), cause);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        RuntimeContext context = activeContext();
        if (context == null) return;
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            context.accumulator.addKill(killer.getUniqueId(), Instant.now(), event.getEntity() instanceof Player);
        }
    }

    private RuntimeContext activeContext() {
        RuntimeContext context = runtime;
        return active && context != null && !context.closed.get() ? context : null;
    }

    private RuntimeContext acquireAsyncContext() {
        RuntimeContext context = activeContext();
        if (context == null) {
            return null;
        }
        context.inFlightAsyncCollectors.incrementAndGet();
        if (!active || runtime != context || context.retired.get() || context.closed.get()) {
            releaseAsyncContext(context);
            return null;
        }
        return context;
    }

    private void releaseAsyncContext(RuntimeContext context) {
        int remaining = context.inFlightAsyncCollectors.decrementAndGet();
        if (remaining == 0 && context.retired.get() && !stopping) {
            submitIo(this::flushRetiredContexts);
        }
    }

    private PlayerState stateFor(RuntimeContext context, UUID uuid) {
        PlayerState state = playerStates.get(uuid);
        return state != null && state.generation == context.generation ? state : null;
    }

    private void markActivity(RuntimeContext context, UUID uuid, Instant now) {
        PlayerState state = stateFor(context, uuid);
        if (state == null) {
            context.accumulator.markActiveMinute(uuid, now);
            return;
        }

        state.lastActivityAt = now;
        long epochMinute = Math.floorDiv(now.getEpochSecond(), 60L);
        long observed = state.lastMarkedActiveEpochMinute.get();
        while (epochMinute > observed) {
            if (state.lastMarkedActiveEpochMinute.compareAndSet(observed, epochMinute)) {
                context.accumulator.markActiveMinute(uuid, now);
                break;
            }
            observed = state.lastMarkedActiveEpochMinute.get();
        }
    }

    private void initializeOnlinePlayers(RuntimeContext context, Instant now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            initializePlayer(context, player, now, true);
        }
    }

    private void initializePlayer(RuntimeContext context, Player player, Instant now, boolean countSession) {
        Location location = player.getLocation();
        PlayerState state = new PlayerState(
            context.generation,
            now,
            player.getWorld().getName(),
            player.getName(),
            location.getX(),
            location.getY(),
            location.getZ()
        );
        playerStates.put(player.getUniqueId(), state);
        context.accumulator.upsertPlayer(player.getUniqueId(), now, now, player.getName());
        if (countSession) {
            context.accumulator.addSessionCount(player.getUniqueId(), now);
        }
    }

    private void tickOnlinePlayers(RuntimeContext context) {
        if (!active || runtime != context) return;
        Instant now = Instant.now();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerState state = stateFor(context, player.getUniqueId());
            if (state == null) {
                initializePlayer(context, player, now, true);
                continue;
            }
            accountPlayer(context, player, state, now);
        }
    }

    private void accountPlayer(RuntimeContext context, Player player, PlayerState state, Instant now) {
        Instant lastTick = state.lastTickAt;
        if (lastTick != null && now.isAfter(lastTick)) {
            long wholeSeconds = Duration.between(lastTick, now).getSeconds();
            if (wholeSeconds > 0) {
                Instant accountedEnd = lastTick.plusSeconds(wholeSeconds);
                addTimeRange(context.accumulator, player.getUniqueId(), lastTick, accountedEnd, false);

                Instant afkStart = state.lastActivityAt.plusSeconds(context.settings.afkThresholdSeconds());
                Instant segmentStart = lastTick.isAfter(afkStart) ? lastTick : afkStart;
                if (accountedEnd.isAfter(segmentStart)) {
                    long afkSeconds = Duration.between(segmentStart, accountedEnd).getSeconds();
                    addTimeRange(context.accumulator, player.getUniqueId(), segmentStart, accountedEnd, true);
                    state.sessionAfkSec += afkSeconds;
                }
                state.lastTickAt = accountedEnd;
            }
        }

        Location location = player.getLocation();
        String world = player.getWorld().getName();
        if (world.equals(state.currentWorld)) {
            double dx = location.getX() - state.lastX;
            double dy = location.getY() - state.lastY;
            double dz = location.getZ() - state.lastZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > 0 && distance < 1000) {
                double total = state.distanceRemainderM + distance;
                long wholeMeters = (long) Math.floor(total);
                state.distanceRemainderM = total - wholeMeters;
                context.accumulator.addDistance(player.getUniqueId(), now, wholeMeters);
            }
        } else {
            state.distanceRemainderM = 0;
        }
        state.currentWorld = world;
        state.lastKnownName = player.getName();
        state.lastX = location.getX();
        state.lastY = location.getY();
        state.lastZ = location.getZ();
    }

    private void addTimeRange(StatsAccumulator accumulator, UUID uuid, Instant start, Instant end, boolean afk) {
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant nextHour = Keys.hourStartInstant(Keys.epochHour(cursor) + 1);
            Instant segmentEnd = nextHour.isBefore(end) ? nextHour : end;
            long seconds = Duration.between(cursor, segmentEnd).getSeconds();
            if (seconds > 0) {
                if (afk) {
                    accumulator.addAfk(uuid, cursor, seconds);
                } else {
                    accumulator.addPlaytime(uuid, cursor, seconds);
                }
            }
            cursor = segmentEnd;
        }
    }

    private void finalizeOnlineSessions(RuntimeContext context, Instant now) {
        for (Map.Entry<UUID, PlayerState> entry : playerStates.entrySet()) {
            PlayerState state = entry.getValue();
            if (state.generation != context.generation) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                accountPlayer(context, player, state, now);
            }
            closeSession(context, entry.getKey(), state.lastKnownName, state, now);
        }
    }

    private void closeSession(RuntimeContext context, UUID uuid, String name, PlayerState state, Instant now) {
        context.accumulator.upsertPlayer(uuid, state.joinAt, now, name);
        long duration = Math.max(0, Duration.between(state.joinAt, now).getSeconds());
        context.accumulator.addSession(new StatsAccumulator.SessionRow(
            uuid,
            state.joinAt,
            now,
            safeInt(duration),
            safeInt(Math.max(0, state.sessionAfkSec)),
            state.joinWorld,
            state.currentWorld
        ));
    }

    private static int safeInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record FlushOutcome(int rows, int snapshots) {}

    private record Settings(
        boolean setupEnabled,
        int tickIntervalSeconds,
        int flushIntervalSeconds,
        int maxBatchRows,
        int afkThresholdSeconds,
        boolean commandsEnabled,
        boolean allowReload,
        boolean allowDbPing,
        boolean allowForceFlush,
        boolean autoCreateTables,
        boolean autoMigrate
    ) {
        private static Settings defaults() {
            return new Settings(false, 5, 300, 5000, 60, true, true, true, true, true, true);
        }

        private static Settings from(FileConfiguration config) {
            int configVersion = config.getInt("config-version", CONFIG_VERSION);
            if (configVersion < 1 || configVersion > CONFIG_VERSION) {
                throw new IllegalArgumentException("Unsupported config-version: " + configVersion);
            }
            DbType.fromConfig(config.getString("database.type", "sqlite"));
            return new Settings(
                config.getBoolean("setup.enabled", false),
                clamp(config.getInt("tick.intervalSeconds", 5), 1, 60),
                clamp(config.getInt("flush.intervalSeconds", 300), 10, 86_400),
                clamp(config.getInt("flush.maxBatchRows", 5000), 1, 100_000),
                clamp(config.getInt("afk.thresholdSeconds", 60), 5, 86_400),
                config.getBoolean("commands.enabled", true),
                config.getBoolean("commands.allowReload", true),
                config.getBoolean("commands.allowDbPing", true),
                config.getBoolean("commands.allowForceFlush", true),
                config.getBoolean("database.migrations.autoCreateTables", true),
                config.getBoolean("database.migrations.autoMigrate", true)
            );
        }
    }

    private static final class RuntimeContext {
        private final long generation;
        private final Settings settings;
        private final StatsAccumulator accumulator;
        private final CommandNormalizer commandNormalizer;
        private final DatabaseManager databaseManager;
        private final DurableStatsWriter writer;
        private final AtomicBoolean flushQueued = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger inFlightAsyncCollectors = new AtomicInteger();
        private volatile StatsAccumulator.FlushSnapshot pendingSnapshot;
        private volatile int pendingRows;

        private RuntimeContext(
            long generation,
            Settings settings,
            StatsAccumulator accumulator,
            CommandNormalizer commandNormalizer,
            DatabaseManager databaseManager,
            DurableStatsWriter writer
        ) {
            this.generation = generation;
            this.settings = settings;
            this.accumulator = accumulator;
            this.commandNormalizer = commandNormalizer;
            this.databaseManager = databaseManager;
            this.writer = writer;
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                databaseManager.close();
            }
        }
    }

    private static final class PlayerState {
        private final long generation;
        private final Instant joinAt;
        private final String joinWorld;
        private volatile String currentWorld;
        private volatile String lastKnownName;
        private volatile Instant lastTickAt;
        private volatile Instant lastActivityAt;
        private volatile long sessionAfkSec;
        private volatile double lastX;
        private volatile double lastY;
        private volatile double lastZ;
        private volatile double distanceRemainderM;
        private final AtomicLong lastMarkedActiveEpochMinute = new AtomicLong(Long.MIN_VALUE);

        private PlayerState(long generation, Instant now, String world, String name, double x, double y, double z) {
            this.generation = generation;
            this.joinAt = now;
            this.joinWorld = world;
            this.currentWorld = world;
            this.lastKnownName = name;
            this.lastTickAt = now;
            this.lastActivityAt = now;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }
    }
}
