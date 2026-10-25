package com.namelessmc.bot;

import com.google.common.base.Preconditions;
import com.namelessmc.bot.Language.LanguageLoadException;
import com.namelessmc.bot.commands.Command;
import com.namelessmc.bot.connections.BackendStorageException;
import com.namelessmc.bot.connections.ConnectionManager;
import com.namelessmc.bot.connections.StorageInitializer;
import com.namelessmc.bot.http.HttpMain;
import com.namelessmc.bot.http.Root;
import com.namelessmc.bot.listeners.CommandListener;
import com.namelessmc.bot.listeners.DiscordRoleListener;
import com.namelessmc.bot.listeners.GuildJoinHandler;
import com.namelessmc.bot.listeners.MemberJoinListener;
import com.namelessmc.java_api.NamelessAPI;
import com.namelessmc.java_api.exception.ApiException;
import com.namelessmc.java_api.exception.NamelessException;
import com.namelessmc.java_api.logger.ApiLogger;
import com.namelessmc.java_api.logger.Slf4jLogger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDA.Status;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class Main {

	public static final String USER_AGENT = "Nameless-Link-Improved/" + Main.class.getPackage().getImplementationVersion();
	private static final String DEFAULT_LANGUAGE_CODE = "en_UK";

	private static JDA[] jda;
	public static JDA getJda(final int shardId) { return jda[shardId]; }
	public static JDA getJdaForGuild(final long guildId) {
		return getJda((int) ((guildId >> 22) % getShardCount()));
	}

	public static Guild getGuildById(final long guildId) {
		return getJdaForGuild(guildId).getGuildById(guildId);
	}

	/**
	 * The default member role id from the {@code DISCORD_ROLE_ID} environment variable, or null when it is not
	 * set. See {@link GuildSettings}.
	 */
	private static @Nullable Long defaultRoleId;
	public static @Nullable Long getDefaultRoleId() { return defaultRoleId; }

	/**
	 * The guilds (by id) the default role is limited to, or an empty list when it applies to every guild.
	 */
	private static List<Long> defaultRoleGuildIds = List.of();
	public static List<Long> getDefaultRoleGuildIds() { return defaultRoleGuildIds; }

	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

	private static ScheduledExecutorService executorService;
	public static ScheduledExecutorService getExecutorService() { return executorService; }

	private static ConnectionManager connectionManager;
	public static ConnectionManager getConnectionManager() { return connectionManager; }

	private static URL botUrl;
	public static URL getBotUrl() { return Objects.requireNonNull(botUrl); }

	private static String webserverInterface;
	public static String getWebserverInterface() { return Objects.requireNonNull(webserverInterface); }

	private static int webserverPort;
	public static int getWebserverPort() { return webserverPort; }

	/**
	 * When false, try to detect local addresses and display a user-friendly warning. This setting will not block
	 * private addresses perfectly, do not rely on it for security!
	 */
	private static boolean localAllowed;
	public static boolean isLocalAllowed() { return localAllowed; }

	private static @Nullable ApiLogger apiDebugLogger;
	public static @Nullable ApiLogger getApiDebugLogger() { return apiDebugLogger; }

	private static int shards;
	public static int getShardCount() { return shards; }

	private static @Nullable Activity getConfiguredActivity() {
		String typeEnv = System.getenv("BOT_ACTIVITY_TYPE");
		String message = System.getenv("BOT_ACTIVITY_MESSAGE");

		if (typeEnv == null || message == null) {
			LOGGER.info("BOT_ACTIVITY_TYPE or BOT_ACTIVITY_MESSAGE not set; skipping status configuration.");
			return null;
		}

		return switch (typeEnv.toUpperCase()) {
			case "PLAYING" -> Activity.playing(message);
			case "LISTENING" -> Activity.listening(message);
			case "WATCHING" -> Activity.watching(message);
			case "COMPETING" -> Activity.competing(message);
			default -> {
				LOGGER.warn("Invalid BOT_ACTIVITY_TYPE: '{}'. Valid options: PLAYING, LISTENING, WATCHING, COMPETING.", typeEnv);
				yield null;
			}
		};
	}

	public static void main(final String[] args) throws BackendStorageException, NamelessException {
		ConfigManager.load();
		LOGGER.info("Starting Nameless Link version {}", Main.class.getPackage().getImplementationVersion());

		int poolSize = 5;
		if (System.getenv("THREAD_POOL_SIZE") != null) {
			poolSize = Integer.parseInt(System.getenv("THREAD_POOL_SIZE"));
			LOGGER.info("Configured thread pool with {} threads", poolSize);
		}
		executorService = Executors.newScheduledThreadPool(poolSize);

		botUrl = StorageInitializer.getEnvUrl("BOT_URL");

		if (System.getenv("SERVER_PORT") != null) {
			LOGGER.info("Environment variable SERVER_PORT is set (by Pterodactyl Panel). Using that instead of WEBSERVER_PORT.");
			webserverPort = (int) StorageInitializer.getEnvLong("SERVER_PORT", null);
		} else {
			webserverPort = (int) StorageInitializer.getEnvLong("WEBSERVER_PORT", null);
		}

		localAllowed = System.getenv("ALLOW_LOCAL_ADDRESSES") != null;

		String defaultLang = StorageInitializer.getEnvString("DEFAULT_LANGUAGE", DEFAULT_LANGUAGE_CODE);
		try {
			Language.setDefaultLanguage(defaultLang);
		} catch (LanguageLoadException e) {
			LOGGER.warn("Unable to set default language, '{}' is not a valid language.", defaultLang);
		}

		if (System.getenv("API_DEBUG") != null && Boolean.parseBoolean(System.getenv("API_DEBUG"))) {
			apiDebugLogger = new Slf4jLogger(LoggerFactory.getLogger("nameless-java-api debug"));
		} else {
			apiDebugLogger = null;
		}

		webserverInterface = StorageInitializer.getEnvString("WEBSERVER_BIND", "127.0.0.1");

		shards = (int) StorageInitializer.getEnvLong("SHARDS", 1L);

		loadDefaultRoleConfig();

		// Temporary workaround for OpenJDK 17 bug
		// https://github.com/DV8FromTheWorld/JDA/issues/1858#issuecomment-942066283
		final int cores = Runtime.getRuntime().availableProcessors();
		if (cores <= 1) {
			LOGGER.info("Available cores {}, setting parallelism flag", cores);
			System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "1");
		}

		initializeConnectionManager();

		String token = StorageInitializer.getEnvString("DISCORD_TOKEN", null);

		jda = new JDA[shards];
		for (int i = 0; i < shards; i++) {
			LOGGER.info("Initializing shard {}", i);

			jda[i] = JDABuilder.createDefault(token)
					.addEventListeners(new GuildJoinHandler())
					.addEventListeners(new CommandListener())
					.addEventListeners(new DiscordRoleListener())
					.addEventListeners(new MemberJoinListener())
					.setActivity(getConfiguredActivity())
					.enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.DIRECT_MESSAGES)
					.setMemberCachePolicy(MemberCachePolicy.ALL)
					.useSharding(i, shards)
					.build();
		}

		LOGGER.info("Waiting for JDA to connect, this can take a long time (30+ seconds is not unusual)...");
		LOGGER.info("Note: the JDA message \"Connected to WebSocket\" does not mean it is finished connecting!");

		try {
			for (int i = 0; i < Main.getShardCount(); i++) {
				Main.getJda(i).awaitStatus(Status.CONNECTED);
				LOGGER.info("Shard {} connected", i);
			}
		} catch (final InterruptedException e) {
			e.printStackTrace();
			System.exit(1);
		}

		LOGGER.info("JDA connected!");

		try {
			LOGGER.info("Starting web server...");
			HttpMain.init();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		sendBotSettings();

		if (!Main.getConnectionManager().isReadOnly()) {
			Main.getExecutorService().scheduleAtFixedRate(
					() -> Main.getExecutorService().execute(ConnectionCleanup::run),
					15, TimeUnit.HOURS.toMinutes(12), TimeUnit.MINUTES);
		}

		Main.getExecutorService().scheduleAtFixedRate(new UsernameSync(), 6, 6, TimeUnit.HOURS);

		MinecraftServerStatus.schedule();

		new Metrics();
	}

	/**
	 * Reads {@code DISCORD_ROLE_ID}, the role assigned to members when they join, and optionally
	 * {@code DISCORD_ROLE_GUILD_IDS}, a comma separated list of guild ids it applies to.
	 *
	 * <p>Nothing is stored anywhere: the value is read once at startup and used for as long as the bot runs.
	 * An empty {@code DISCORD_ROLE_ID} is treated as "not set", so emptying the variable disables the
	 * feature. A value that is not a valid Discord snowflake stops startup, since a typo here would otherwise
	 * silently assign no role at all.</p>
	 */
	private static void loadDefaultRoleConfig() {
		final String roleIdEnv = System.getenv("DISCORD_ROLE_ID");
		final String guildIdsEnv = System.getenv("DISCORD_ROLE_GUILD_IDS");

		if (roleIdEnv == null || roleIdEnv.isBlank()) {
			defaultRoleId = null;
			defaultRoleGuildIds = List.of();
			if (roleIdEnv != null) {
				LOGGER.info("DISCORD_ROLE_ID is empty, no default role will be assigned.");
			} else {
				LOGGER.info("DISCORD_ROLE_ID not set, no default role will be assigned.");
			}
			return;
		}

		final long roleId;
		try {
			roleId = Long.parseLong(roleIdEnv.trim());
		} catch (final NumberFormatException e) {
			LOGGER.error("The value of DISCORD_ROLE_ID ('{}') is not a valid Discord role id. Discord role ids are numeric snowflakes, for example 123456789012345678.", roleIdEnv);
			System.exit(1);
			return;
		}

		if (roleId <= 0) {
			LOGGER.error("The value of DISCORD_ROLE_ID ('{}') is not a valid Discord role id. Discord role ids are numeric snowflakes, for example 123456789012345678.", roleIdEnv);
			System.exit(1);
			return;
		}

		defaultRoleId = roleId;

		if (guildIdsEnv == null || guildIdsEnv.isBlank()) {
			defaultRoleGuildIds = List.of();
			LOGGER.info("DISCORD_ROLE_ID is set to {}. New members will get this role in every server this bot is in.", roleId);
			return;
		}

		final List<Long> guildIds = new ArrayList<>();
		for (final String part : guildIdsEnv.split(",")) {
			final String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				guildIds.add(Long.parseLong(trimmed));
			} catch (final NumberFormatException e) {
				LOGGER.error("The value of DISCORD_ROLE_GUILD_IDS ('{}') contains an invalid guild id: '{}'. Guild ids are numeric snowflakes.", guildIdsEnv, trimmed);
				System.exit(1);
				return;
			}
		}

		defaultRoleGuildIds = List.copyOf(guildIds);
		if (defaultRoleGuildIds.isEmpty()) {
			LOGGER.info("DISCORD_ROLE_ID is set to {}. New members will get this role in every server this bot is in.", roleId);
		} else {
			LOGGER.info("DISCORD_ROLE_ID is set to {}. New members will get this role in guilds: {}", roleId, defaultRoleGuildIds);
		}
	}

	public static GuildSettings getGuildSettings(final Guild guild) {
		return new GuildSettings(guild);
	}

	public static void canModifySettings(final User user, final Guild guild, final Consumer<Boolean> canModifySettings) {
		guild.retrieveMember(user).queue(
				// success
				member -> canModifySettings.accept(member.hasPermission(Permission.ADMINISTRATOR)),
				// failure
				t -> {
					LOGGER.warn("Error while retrieving member in canModifySettings, assuming user {} is not allowed to modify settings.", user.getId());
					canModifySettings.accept(false);
				});
	}

	private static void initializeConnectionManager() {
		String storageType = System.getenv("STORAGE_TYPE");
		if (storageType == null) {
			LOGGER.info("STORAGE_TYPE not specified, assuming STORAGE_TYPE=stateless");
			storageType = "stateless";
		}

		final StorageInitializer<? extends ConnectionManager> init = StorageInitializer.getByName(storageType);
		if (init == null) {
			LOGGER.error("The chosen STORAGE_TYPE is not available, please choose from: {}",
					String.join(", ", StorageInitializer.getAvailableNames()));
			System.exit(1);
		}

		connectionManager = init.get();
	}

	private static void sendBotSettings() throws NamelessException, BackendStorageException {
		final User user = Main.getJda(0).getSelfUser();
		final String username = user.getName();
		if (Main.getConnectionManager().isReadOnly()) {
			final Collection<NamelessAPI> apiConnections = connectionManager.listConnections();
			Preconditions.checkArgument(apiConnections.size() == 1, "Stateless connection manager should always have 1 connection");
			final NamelessAPI api = apiConnections.iterator().next();
			final long guildId = connectionManager.getGuildIdByApiConnection(api).orElseThrow();
			LOGGER.info("Sending bot settings to " + api.apiUrl());
			api.discord().updateBotSettings(botUrl, guildId, username, user.getIdLong());
			final Guild guild = Main.getJda(0).getGuildById(guildId);
			if (guild == null) {
				LOGGER.error("Guild with id '{}' does not exist. Is the ID wrong or is the bot not in this guild?", guildId);
				System.exit(1);
			}
			try {
				Command.sendCommands(guild);
			} catch (ErrorResponseException e) {
				LOGGER.error("Failed to register slash commands: " + e.getMessage());
				LOGGER.error("Make sure you invite the bot with the 'applications.commands' scope enabled.");
			}
			DiscordRoleListener.sendRolesAsync(guildId);
			LOGGER.info("Sent bot settings to website and registered commands successfully.");
		} else {
			int threads;
			if (System.getenv("UPDATE_SETTINGS_THREADS") != null) {
				threads = Integer.parseInt(System.getenv("UPDATE_SETTINGS_THREADS"));
			} else {
				threads = 2;
			}

			final ExecutorService service = Executors.newFixedThreadPool(threads);

			final AtomicInteger countTotal = new AtomicInteger();
			final AtomicInteger countSuccess = new AtomicInteger();
			final AtomicInteger countError = new AtomicInteger();

			LOGGER.info("Updating bot settings and sending slash commands...");

			for (int shard = 0; shard < getShardCount(); shard++) {
				for (final Guild guild : getJda(shard).getGuilds()) {
					service.execute(() -> {
						try {
							Command.sendCommands(guild);
						} catch (ErrorResponseException e) {
							LOGGER.warn("{} failed to send commands: {}", guild.getIdLong(), e.getMessage());
						}

						try {
							final NamelessAPI api = connectionManager.getApiConnection(guild.getIdLong());
							if (api != null) {
								try {
									api.discord().updateBotSettings(botUrl, guild.getIdLong(), username, user.getIdLong());
									LOGGER.info("{} sent commands, sent settings to {}", guild.getIdLong(), api.apiUrl());
									countSuccess.incrementAndGet();
								} catch (final NamelessException e) {
									LOGGER.info("{} sent commands, failed to send settings to {}", guild.getIdLong(), api.apiUrl());
									countError.incrementAndGet();
								}
							} else {
								LOGGER.info("{} sent commands", guild.getIdLong());
							}
						} catch (final BackendStorageException e) {
							LOGGER.error(guild.getIdLong() + " backend storage exception", e);
						}

						countTotal.incrementAndGet();
					});
				}
			}

			service.shutdown();
			try {
				service.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
			} catch (final InterruptedException e) {
				e.printStackTrace();
			}
			LOGGER.info("Done updating bot settings");
			LOGGER.info("{} total guilds, {} websites successful, {} websites unsuccessful", countTotal, countSuccess, countError);
			Root.pingSuccessCount = countSuccess.get();
			Root.pingFailCount = countError.get();
		}
	}

	private static final Set<Class<?>> IGNORED_EXCEPTIONS = Set.of(
			UnknownHostException.class,
			SSLHandshakeException.class,
			CertificateException.class,
			SocketTimeoutException.class,
			ConnectException.class
	);

	public static void logConnectionError(final Logger logger, final @Nullable String message, final NamelessException e) {
		Objects.requireNonNull(logger, "Logger is null");
		Objects.requireNonNull(e, "Exception is null");
		if (e instanceof ApiException apiException) {
			if (message != null) {
				logger.warn(message + " (API error {})", apiException.apiError());
			} else {
				logger.warn("API error {}", apiException.apiError());
			}
		} else if (e.getCause() != null &&
				apiDebugLogger == null &&
				IGNORED_EXCEPTIONS.contains(e.getCause().getClass())) {
			if (message != null) {
				logger.warn(message + " ({})", e.getCause().getClass().getSimpleName());
			} else {
				logger.warn(e.getCause().getClass().getSimpleName());
			}
		} else {
			logger.warn(Objects.requireNonNullElse(message, "Unexpected connection error"), e);
		}
	}

	public static void logConnectionError(final Logger logger, final NamelessException e) {
		logConnectionError(logger, null, e);
	}

}
