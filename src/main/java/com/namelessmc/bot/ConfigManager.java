package com.namelessmc.bot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads configuration from a {@code config.yml} file located in the same directory as the jar.
 *
 * <p>This replaces environment variables as the source of all bot settings. On first launch, when no config
 * file exists, a template with blank values is created and the bot exits with instructions. On subsequent
 * launches, required settings are validated; missing values cause the bot to exit with a message listing
 * what is missing.</p>
 *
 * <p>The file is read once at startup. There is no live reload: change the file and restart the bot.</p>
 */
public class ConfigManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);

	private static final String CONFIG_FILENAME = "config.yml";

	private static Path configPath;
	private static Map<String, String> values = new HashMap<>();

	private ConfigManager() {
	}

	/**
	 * Locates {@code config.yml} next to the jar, creating a template when absent, then loads and validates
	 * it. Exits the process when required settings are missing.
	 */
	public static void load() {
		configPath = findConfigPath();

		if (!Files.exists(configPath)) {
			createTemplate();
			System.err.println();
			System.err.println("Config file not found. A template has been created at:");
			System.err.println("  " + configPath.toAbsolutePath());
			System.err.println();
			System.err.println("Config file is empty or invalid, please configure the config.yml file.");
			System.err.println("Fill in the required settings, then start the bot again.");
			System.exit(1);
		}

		parseFile();
		ensureAllKeys();
		parseFile();
		validate();
	}

	/**
	 * Returns the value for a key, or null when the key is missing or blank.
	 */
	@Nullable
	public static String get(final String key) {
		final String value = values.get(key);
		if (value == null || value.isEmpty()) {
			return null;
		}
		return value;
	}

	/**
	 * Returns the value for a key, or the given default when it is missing or blank.
	 */
	public static String get(final String key, final String defaultValue) {
		final String value = get(key);
		if (value != null) {
			return value;
		}
		return defaultValue;
	}

	/**
	 * Returns whether a key is present in the config file at all, regardless of its value. This preserves
	 * the environment-variable behavior where a flag set to an empty string is still "set".
	 */
	public static boolean containsKey(final String key) {
		return values.containsKey(key);
	}

	/**
	 * Parses a comma-separated list of Discord snowflake ids. Blank parts are skipped; a malformed id is
	 * logged and skipped rather than aborting, so one typo does not block an otherwise valid list.
	 */
	public static List<Long> getLongList(final String key) {
		final String raw = get(key);
		if (raw == null) {
			return List.of();
		}
		final List<Long> result = new ArrayList<>();
		for (final String part : raw.split(",")) {
			final String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				result.add(Long.parseLong(trimmed));
			} catch (final NumberFormatException e) {
			 LOGGER.warn("The value of {} ('{}') contains an invalid id: '{}'. Skipping it.", key, raw, trimmed);
			}
		}
		return result;
	}

	/**
	 * Returns the path of the loaded config file, for diagnostics.
	 */
	public static Path getConfigPath() {
		return configPath;
	}

	private static Path findConfigPath() {
		try {
			final Path jarPath = Paths.get(ConfigManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return jarPath.getParent().resolve(CONFIG_FILENAME);
		} catch (URISyntaxException | IllegalArgumentException e) {
			// Fall back to the working directory when the jar path cannot be resolved (e.g. running from an
			// IDE, or a custom classloader that does not expose the code source location).
			return Paths.get(System.getProperty("user.dir"), CONFIG_FILENAME);
		}
	}

	private static void createTemplate() {
		try {
			Files.createDirectories(configPath.getParent());
			Files.writeString(configPath, CONFIG_TEMPLATE);
		} catch (final IOException e) {
			System.err.println("Failed to create config file at " + configPath.toAbsolutePath() + ": " + e.getMessage());
			System.exit(1);
		}
	}

	private static void parseFile() {
		try (InputStream is = Files.newInputStream(configPath)) {
			final Yaml yaml = new Yaml();
			final Map<String, Object> loaded = yaml.load(is);
			values = new HashMap<>();
			if (loaded != null) {
				for (final Map.Entry<String, Object> entry : loaded.entrySet()) {
					values.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
				}
			}
			if (values.isEmpty()) {
				System.err.println("Config file is empty or invalid, please configure the config.yml file.");
				System.err.println("File location: " + configPath.toAbsolutePath());
				System.exit(1);
			}
		} catch (final IOException e) {
			System.err.println("Failed to read config file: " + e.getMessage());
			System.exit(1);
		}
	}

	private static void validate() {
		final List<String> missing = new ArrayList<>();

		for (final String key : List.of("DISCORD_TOKEN", "BOT_URL", "WEBSERVER_PORT")) {
			if (get(key) == null) {
				missing.add(key);
			}
		}

		final String storageType = get("STORAGE_TYPE");
		if (storageType == null || storageType.equals("stateless")) {
			for (final String key : List.of("GUILD_ID", "API_URL", "API_KEY")) {
				if (get(key) == null) {
					missing.add(key);
				}
			}
		} else if (storageType.equals("postgres")) {
			for (final String key : List.of("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")) {
				if (get(key) == null) {
					missing.add(key);
				}
			}
		}

		if (!missing.isEmpty()) {
			resetKeys(missing);
			System.err.println();
			System.err.println("Config file is empty or invalid, please configure the config.yml file.");
			System.err.println("The following settings were missing/invalid and have been reset to blank:");
			for (final String key : missing) {
				System.err.println("  - " + key);
			}
			System.err.println("File location: " + configPath.toAbsolutePath());
			System.exit(1);
		}
	}

	/**
	 * Resets the given keys to a blank value in config.yml, preserving the rest of the file.
	 */
	private static void resetKeys(final List<String> keys) {
		try {
			final List<String> lines = Files.readAllLines(configPath);
			final List<String> out = new ArrayList<>();
			for (final String line : lines) {
				boolean replaced = false;
				for (final String key : keys) {
					if (line.trim().startsWith(key + ":")) {
						out.add(key + ": \"\"");
						replaced = true;
						break;
					}
				}
				if (!replaced) {
					out.add(line);
				}
			}
			Files.write(configPath, out);
		} catch (final IOException e) {
			LOGGER.warn("Could not reset config lines: {}", e.getMessage());
		}
	}

private static void ensureAllKeys() {
		final List<String> known = new ArrayList<>();
		for (final String line : CONFIG_TEMPLATE.split("\n")) {
			final String t = line.trim();
			if (t.matches("^[A-Z_]+:.*")) {
				known.add(t.split(":")[0].trim());
			}
		}
		try {
			final List<String> lines = Files.readAllLines(configPath);
			final java.util.Set<String> present = new java.util.HashSet<>();
			for (final String line : lines) {
				final String t = line.trim();
				if (t.matches("^[A-Z_]+:.*")) {
					present.add(t.split(":")[0].trim());
				}
			}
			boolean changed = false;
			final List<String> out = new ArrayList<>(lines);
			for (final String key : known) {
				if (!present.contains(key)) {
					out.add(key + ": \"\"");
					changed = true;
				}
			}
			if (changed) {
				Files.write(configPath, out);
			}
		} catch (final IOException e) {
			LOGGER.warn("Could not ensure config keys: {}", e.getMessage());
		}
	}

private static final String CONFIG_TEMPLATE = """
			# Nameless-Link Configuration
			# ===========================
			# This file replaces environment variables. Fill in the values below and restart the bot.
			# Lines starting with # are comments and are ignored.
			#
			# REQUIRED SETTINGS - the bot will not start without these
			# --------------------------------------------------------

			# Discord bot token. REQUIRED.
			# Get this from the Discord Developer Portal: https://discord.com/developers/applications
			DISCORD_TOKEN: ""

			# Public URL where the bot's webserver can be reached. REQUIRED.
			# Must be reachable from your NamelessMC website, e.g. "http://localhost:27362"
			BOT_URL: ""

			# Port for the bot's internal webserver. REQUIRED.
			# If blank, the bot will not start.
			WEBSERVER_PORT: ""

			# Discord guild (server) ID. REQUIRED in stateless mode.
			# Enable Developer Mode in Discord, then right-copy the server ID.
			GUILD_ID: ""

			# NamelessMC API URL. REQUIRED in stateless mode.
			# Found in StaffCP > Configuration > API. Must end with "route=/api/v2"
			API_URL: ""

			# NamelessMC API key. REQUIRED in stateless mode.
			# Found in StaffCP > Configuration > API.
			API_KEY: ""

			# OPTIONAL SETTINGS - safe defaults are noted for each
			# ------------------------------------------------------

			# Interface to bind the webserver to. Optional.
			# Default: "127.0.0.1". Set to "0.0.0.0" to accept connections from other machines.
			WEBSERVER_BIND: ""

			# Storage backend. Optional.
			# "stateless" = no database, uses API_URL/API_KEY/GUILD_ID above.
			# "postgres" = uses PostgreSQL for multi-guild support.
			# Default: "stateless"
			STORAGE_TYPE: ""

			# PostgreSQL hostname. Optional, only used when STORAGE_TYPE=postgres.
			# Default: "localhost"
			POSTGRES_HOSTNAME: ""

			# PostgreSQL port. Optional, only used when STORAGE_TYPE=postgres.
			# Default: "5432"
			POSTGRES_PORT: ""

			# PostgreSQL database name. Required when STORAGE_TYPE=postgres.
			POSTGRES_DB: ""

			# PostgreSQL username. Required when STORAGE_TYPE=postgres.
			POSTGRES_USER: ""

			# PostgreSQL password. Required when STORAGE_TYPE=postgres.
			POSTGRES_PASSWORD: ""

			# Pterodactyl panel override for the webserver port. Optional.
			# When set, Pterodactyl assigns the port and this takes precedence over WEBSERVER_PORT.
			SERVER_PORT: ""

			# Role assigned to members when they join the server. Optional.
			# If blank, no default role is assigned.
			# Find role IDs by enabling Developer Mode, then right-clicking the role in server settings.
			# Example: "123456789012345678"
			DISCORD_ROLE_ID: ""

			# Comma-separated guild IDs the default role applies to. Optional.
			# If blank, the default role applies to every guild the bot is in.
			# Example: "123456789012345678,876543210987654321"
			DISCORD_ROLE_GUILD_IDS: ""

			# Comma-separated Discord role IDs allowed to use bot commands. Optional.
			# If blank, only Administrators can run commands (Discord default for slash commands).
			# If set, ONLY members with at least one of these roles can run any bot command.
			# Example: "123456789012345678" or "123456789012345678,876543210987654321"
			COMMAND_ROLES: ""

			# Sync NamelessMC usernames to Discord nicknames. Optional.
			# "true" = enabled, "false" = disabled.
			# Default: "false"
			ENABLE_USERNAME_SYNC: ""

			# Default language code. Optional.
			# See src/main/resources/languages/ for available codes (enter without .json).
			# Default: "en_UK"
			DEFAULT_LANGUAGE: ""

			# Bot activity type. Optional. One of: PLAYING, LISTENING, WATCHING, COMPETING.
			# If blank or invalid, no activity is shown.
			BOT_ACTIVITY_TYPE: ""

			# Bot activity message. Optional. The text shown as the bot's status.
			# Only used when BOT_ACTIVITY_TYPE is set.
			BOT_ACTIVITY_MESSAGE: ""

			# Enable verbose API debug logging. Optional.
			# "true" = enabled, "false" = disabled.
			# Default: "false"
			API_DEBUG: ""

			# Allow private network addresses in API URL. Optional.
			# "true" = allow localhost/private IPs, "false" = block them.
			# Default: "false"
			ALLOW_LOCAL_ADDRESSES: ""

			# Disable the GUILD_MEMBERS intent requirement. Optional.
			# "true" = disables the requirement. NOTE: this will break group sync.
			# Default: "false"
			DISABLE_MEMBERS_INTENT: ""

			# Number of threads for updating bot settings at startup. Optional.
			# Default: "2"
			UPDATE_SETTINGS_THREADS: ""

			# Number of Discord gateway shards. Optional.
			# Default: "1"
			SHARDS: ""

			# Marks this as the official hosted bot instance. Optional.
			# Do not change this unless you are running the official bot.
			OFFICIAL_BOT: ""

			# ---- Server status channel feature ----
			# Discord voice channel ID renamed to show server online/offline status. Optional.
			# Leave blank to disable. Example: "123456789012345678"
			SERVER_STATUS_CHANNEL_ID: ""

			# Discord voice channel ID renamed to show player count (online/max). Optional.
			# Leave blank to disable. Example: "123456789012345678"
			PLAYER_COUNT_CHANNEL_ID: ""

			# Text used for the status channel when the server is online. Optional.
			# Default: "Status: 🟢 Online"
			SERVER_STATUS_ONLINE_TEXT: ""

			# Text used for the status channel when the server is offline. Optional.
			# Default: "Status: 🔴 Offline"
			SERVER_STATUS_OFFLINE_TEXT: ""

			# Text used for the player count channel. Use {players} and {max} placeholders. Optional.
			# Default: "Playercount: {players}/{max}"
			PLAYER_COUNT_TEXT: ""

			# Minecraft server host for the status ping. Optional.
			# Default: "mc.example.com"
			SERVER_STATUS_HOST: ""

			# Minecraft server port for the status ping. Optional.
			# Default: "25565"
			SERVER_STATUS_PORT: ""

			# How often (in seconds) to update the status channels. Optional.
			# Default: "30"
			SERVER_STATUS_UPDATE_INTERVAL_SECONDS: ""
			""";
}
