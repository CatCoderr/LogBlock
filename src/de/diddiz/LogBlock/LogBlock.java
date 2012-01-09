package de.diddiz.LogBlock;

import static de.diddiz.util.Utils.download;
import static org.bukkit.Bukkit.getLogger;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import com.nijiko.permissions.PermissionHandler;
import com.nijikokun.bukkit.Permissions.Permissions;
import de.diddiz.util.MySQLConnectionPool;

public class LogBlock extends JavaPlugin
{
	private static LogBlock logblock = null;
	private Config config;
	private MySQLConnectionPool pool;
	private Consumer consumer = null;
	private CommandsHandler commandsHandler;
	private Updater updater = null;
	private Timer timer = null;
	private PermissionHandler permissions = null;
	private boolean errorAtLoading = false, noDb = false, connected = true;

	public static LogBlock getInstance() {
		return logblock;
	}

	public Config getLBConfig() {
		return config;
	}

	public Consumer getConsumer() {
		return consumer;
	}

	public CommandsHandler getCommandsHandler() {
		return commandsHandler;
	}

	Updater getUpdater() {
		return updater;
	}

	@Override
	public void onLoad() {
		logblock = this;
		try {
			updater = new Updater(this);
			config = new Config(this);
			if (config.checkVersion)
				getLogger().info("[LogBlock] Version check: " + updater.checkVersion());
			getLogger().info("[LogBlock] Connecting to " + config.user + "@" + config.url + "...");
			pool = new MySQLConnectionPool(config.url, config.user, config.password);
			final Connection conn = getConnection();
			if (conn == null) {
				noDb = true;
				return;
			}
			conn.close();
			if (updater.update())
				config = new Config(this);
			updater.checkTables();
		} catch (final NullPointerException ex) {
			getLogger().log(Level.SEVERE, "[LogBlock] Error while loading: ", ex);
		} catch (final Exception ex) {
			getLogger().severe("[LogBlock] Error while loading: " + ex.getMessage());
			errorAtLoading = true;
			return;
		}
		consumer = new Consumer(this);
	}

	@Override
	public void onEnable() {
		final PluginManager pm = getServer().getPluginManager();
		if (errorAtLoading) {
			pm.disablePlugin(this);
			return;
		}
		if (noDb)
			return;
		if (pm.getPlugin("WorldEdit") == null && !new File("lib/WorldEdit.jar").exists() && !new File("WorldEdit.jar").exists())
			try {
				download(getLogger(), new URL("http://diddiz.insane-architects.net/download/WorldEdit.jar"), new File("lib/WorldEdit.jar"));
				getLogger().info("[LogBlock] You've to restart/reload your server now.");
				pm.disablePlugin(this);
				return;
			} catch (final Exception ex) {
				getLogger().warning("[LogBlock] Failed to download WorldEdit. You may have to download it manually. You don't have to install it, just place the jar in the lib folder.");
			}
		commandsHandler = new CommandsHandler(this);
		getCommand("lb").setExecutor(commandsHandler);
		if (pm.getPlugin("Permissions") != null) {
			permissions = ((Permissions)pm.getPlugin("Permissions")).getHandler();
			getLogger().info("[LogBlock] Permissions plugin found.");
		} else
			getLogger().info("[LogBlock] Permissions plugin not found. Using Bukkit Permissions.");
		if (config.enableAutoClearLog && config.autoClearLogDelay > 0)
			getServer().getScheduler().scheduleAsyncRepeatingTask(this, new AutoClearLog(this), 6000, config.autoClearLogDelay * 20);
		getServer().getScheduler().scheduleAsyncDelayedTask(this, new DumpedLogImporter(this));
		final Listener lbBlockListener = new LBBlockListener(this);
		final Listener lbPlayerListener = new LBPlayerListener(this);
		final Listener lbEntityListener = new LBEntityListener(this);
		final Listener lbToolListener = new LBToolListener(this);
		pm.registerEvent(Type.PLAYER_INTERACT, lbToolListener, Priority.Normal, this);
		pm.registerEvent(Type.PLAYER_CHANGED_WORLD, lbToolListener, Priority.Normal, this);
		if (config.askRollbackAfterBan)
			pm.registerEvent(Type.PLAYER_COMMAND_PREPROCESS, lbToolListener, Priority.Normal, this);
		if (config.isLogging(Logging.BLOCKPLACE)) {
			pm.registerEvent(Type.BLOCK_PLACE, lbBlockListener, Priority.Monitor, this);
			pm.registerEvent(Type.PLAYER_BUCKET_EMPTY, lbPlayerListener, Priority.Monitor, this);
		}
		if (config.isLogging(Logging.BLOCKBREAK)) {
			pm.registerEvent(Type.BLOCK_BREAK, lbBlockListener, Priority.Monitor, this);
			pm.registerEvent(Type.PLAYER_BUCKET_FILL, lbPlayerListener, Priority.Monitor, this);
			pm.registerEvent(Type.BLOCK_FROMTO, lbBlockListener, Priority.Monitor, this);
		}
		if (config.isLogging(Logging.SIGNTEXT))
			pm.registerEvent(Type.SIGN_CHANGE, lbBlockListener, Priority.Monitor, this);
		if (config.isLogging(Logging.FIRE))
			pm.registerEvent(Type.BLOCK_BURN, lbBlockListener, Priority.Monitor, this);
		if (config.isLogging(Logging.SNOWFORM))
			pm.registerEvent(Type.BLOCK_FORM, lbBlockListener, Priority.Monitor, this);
		if (config.isLogging(Logging.SNOWFADE))
			pm.registerEvent(Type.BLOCK_FADE, lbBlockListener, Priority.Monitor, this);
		if (config.isLogging(Logging.CREEPEREXPLOSION) || config.isLogging(Logging.TNTEXPLOSION) || config.isLogging(Logging.GHASTFIREBALLEXPLOSION) || config.isLogging(Logging.ENDERDRAGON) || config.isLogging(Logging.MISCEXPLOSION))
			pm.registerEvent(Type.ENTITY_EXPLODE, lbEntityListener, Priority.Monitor, this);
		if (config.isLogging(Logging.LEAVESDECAY))
			pm.registerEvent(Type.LEAVES_DECAY, lbBlockListener, Priority.Monitor, this);
		if (config.isLogging(Logging.CHESTACCESS))
			if (pm.isPluginEnabled("Spout")) {
				pm.registerEvent(Type.CUSTOM_EVENT, new LBSpoutChestAccessListener(this), Priority.Monitor, this);
				getLogger().info("[LogBlock] Using Spout as chest access API");
			} else {
				final Listener chestAccessListener = new LBChestAccessListener(this);
				pm.registerEvent(Type.PLAYER_INTERACT, chestAccessListener, Priority.Monitor, this);
				pm.registerEvent(Type.PLAYER_CHAT, chestAccessListener, Priority.Monitor, this);
				pm.registerEvent(Type.PLAYER_COMMAND_PREPROCESS, chestAccessListener, Priority.Monitor, this);
				pm.registerEvent(Type.PLAYER_TELEPORT, chestAccessListener, Priority.Monitor, this);
				pm.registerEvent(Type.PLAYER_QUIT, chestAccessListener, Priority.Monitor, this);
				getLogger().info("[LogBlock] Using own chest access API");
			}
		if (config.isLogging(Logging.SWITCHINTERACT) || config.isLogging(Logging.DOORINTERACT) || config.isLogging(Logging.CAKEEAT))
			pm.registerEvent(Type.PLAYER_INTERACT, lbPlayerListener, Priority.Monitor, this);
		if (config.isLogging(Logging.KILL))
			pm.registerEvent(Type.ENTITY_DAMAGE, lbEntityListener, Priority.Monitor, this);
		if (config.isLogging(Logging.CHAT)) {
			pm.registerEvent(Type.PLAYER_CHAT, lbPlayerListener, Priority.Monitor, this);
			pm.registerEvent(Type.PLAYER_COMMAND_PREPROCESS, lbPlayerListener, Priority.Monitor, this);
			pm.registerEvent(Type.SERVER_COMMAND, new LBServerListener(this), Priority.Monitor, this);
		}
		if (config.isLogging(Logging.ENDERMEN)) {
			pm.registerEvent(Type.ENDERMAN_PICKUP, lbEntityListener, Priority.Monitor, this);
			pm.registerEvent(Type.ENDERMAN_PLACE, lbEntityListener, Priority.Monitor, this);
		}
		if (config.isLogging(Logging.NATURALSTRUCTUREGROW) || config.isLogging(Logging.BONEMEALSTRUCTUREGROW))
			pm.registerEvent(Type.STRUCTURE_GROW, new LBWorldListener(this), Priority.Monitor, this);
		if (config.logPlayerInfo) {
			pm.registerEvent(Type.PLAYER_JOIN, lbPlayerListener, Priority.Monitor, this);
			pm.registerEvent(Type.PLAYER_QUIT, lbPlayerListener, Priority.Monitor, this);
		}
		if (config.useBukkitScheduler) {
			if (getServer().getScheduler().scheduleAsyncRepeatingTask(this, consumer, config.delayBetweenRuns * 20, config.delayBetweenRuns * 20) > 0)
				getLogger().info("[LogBlock] Scheduled consumer with bukkit scheduler.");
			else {
				getLogger().warning("[LogBlock] Failed to schedule consumer with bukkit scheduler. Now trying schedule with timer.");
				timer = new Timer();
				timer.scheduleAtFixedRate(consumer, config.delayBetweenRuns * 1000, config.delayBetweenRuns * 1000);
			}
		} else {
			timer = new Timer();
			timer.scheduleAtFixedRate(consumer, config.delayBetweenRuns * 1000, config.delayBetweenRuns * 1000);
			getLogger().info("[LogBlock] Scheduled consumer with timer.");
		}
		for (final Tool tool : config.toolsByType.values())
			if (pm.getPermission("logblock.tools." + tool.name) == null) {
				final Permission perm = new Permission("logblock.tools." + tool.name, tool.permissionDefault);
				pm.addPermission(perm);
			}
		// perm.addParent("logblock.*", true);
		getLogger().info("LogBlock v" + getDescription().getVersion() + " by DiddiZ enabled.");
	}

	@Override
	public void onDisable() {
		if (timer != null)
			timer.cancel();
		getServer().getScheduler().cancelTasks(this);
		if (consumer != null) {
			if (config.logPlayerInfo && getServer().getOnlinePlayers() != null)
				for (final Player player : getServer().getOnlinePlayers())
					consumer.queueLeave(player);
			if (consumer.getQueueSize() > 0) {
				getLogger().info("[LogBlock] Waiting for consumer ...");
				int tries = 10;
				while (consumer.getQueueSize() > 0) {
					getLogger().info("[LogBlock] Remaining queue size: " + consumer.getQueueSize());
					if (tries > 0)
						getLogger().info("[LogBlock] Remaining tries: " + tries);
					else {
						getLogger().info("Unable to save queue to database. Trying to write to a local file.");
						try {
							consumer.writeToFile();
							getLogger().info("Successfully dumped queue.");
						} catch (final FileNotFoundException ex) {
							getLogger().info("Failed to write. Given up.");
							break;
						}
					}
					consumer.run();
					tries--;
				}
			}
		}
		if (pool != null)
			pool.close();
		getLogger().info("LogBlock disabled.");
	}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
		if (noDb)
			sender.sendMessage(ChatColor.RED + "No database connected. Check your MySQL user/pw and database for typos. Start/restart your MySQL server.");
		return true;
	}

	boolean hasPermission(CommandSender sender, String permission) {
		if (permissions != null && sender instanceof Player)
			return permissions.has((Player)sender, permission);
		return sender.hasPermission(permission);
	}

	public Connection getConnection() {
		try {
			final Connection conn = pool.getConnection();
			if (!connected) {
				getLogger().info("[LogBlock] MySQL connection rebuild");
				connected = true;
			}
			return conn;
		} catch (final Exception ex) {
			if (connected) {
				getLogger().log(Level.SEVERE, "[LogBlock] Error while fetching connection: ", ex);
				connected = false;
			} else
				getLogger().severe("[LogBlock] MySQL connection lost");
			return null;
		}
	}

	/**
	 * @param params
	 * QueryParams that contains the needed columns (all other will be filled with default values) and the params. World is required.
	 */
	public List<BlockChange> getBlockChanges(QueryParams params) throws SQLException {
		final Connection conn = getConnection();
		Statement state = null;
		if (conn == null)
			throw new SQLException("No connection");
		try {
			state = conn.createStatement();
			final ResultSet rs = state.executeQuery(params.getQuery());
			final List<BlockChange> blockchanges = new ArrayList<BlockChange>();
			while (rs.next())
				blockchanges.add(new BlockChange(rs, params));
			return blockchanges;
		} finally {
			if (state != null)
				state.close();
			conn.close();
		}
	}

	public int getCount(QueryParams params) throws SQLException {
		final Connection conn = getConnection();
		Statement state = null;
		if (conn == null)
			throw new SQLException("No connection");
		try {
			state = conn.createStatement();
			final QueryParams p = params.clone();
			p.needCount = true;
			final ResultSet rs = state.executeQuery(p.getQuery());
			if (!rs.next())
				return 0;
			return rs.getInt(1);
		} finally {
			if (state != null)
				state.close();
			conn.close();
		}
	}
}
