# Nameless-Link Improved

The "Nameless Link" Discord bot synchronizes user roles to and from a specific Discord Guild, with added quality-of-life features: a `config.yml`-based configuration, command-role gating, default member roles, and a Minecraft server status display in Discord voice channels.

## Quick start

```sh
java -jar Nameless-Link-Improved.jar
```

On first run the bot prints:

```
Config file not found. A template has been created at:
  /path/to/config.yml

Config file is empty or invalid, please configure the config.yml file.
Fill in the required settings, then start the bot again.
```

Fill in the file, run again. The bot starts with no environment variables needed.

## Required settings

| Key | What it is |
|---|---|
| `DISCORD_TOKEN` | Discord bot token from the [Developer Portal](https://discord.com/developers/applications) |
| `BOT_URL` | Public URL of the bot's webserver, e.g. `http://localhost:27362`. Must be reachable from your NamelessMC site |
| `WEBSERVER_PORT` | Port for the bot's internal webserver |
| `GUILD_ID` | Discord server ID (stateless mode) |
| `API_URL` | NamelessMC API URL from StaffCP > Configuration > API, ending in `route=/api/v2` (stateless mode) |
| `API_KEY` | NamelessMC API key from StaffCP > Configuration > API (stateless mode) |

Stateless mode (default) needs the three NamelessMC values above. Set `STORAGE_TYPE: "postgres"` instead and the bot uses `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (plus optional `POSTGRES_HOSTNAME` / `POSTGRES_PORT`) — the NamelessMC fields are then not required.

## Server status feature

The bot can rename Discord voice channels to reflect your Minecraft server status, polled via the built-in Server List Ping (TCP, no query mode required).

Configure these keys in `config.yml`:

| Key | Purpose |
|---|---|
| `SERVER_STATUS_CHANNEL_ID` | Voice channel renamed with server status |
| `PLAYER_COUNT_CHANNEL_ID` | Voice channel renamed with player count |
| `SERVER_STATUS_HOST` | Minecraft server host (default `mc.example.com`) |
| `SERVER_STATUS_PORT` | Minecraft server port (default `25565`) |
| `SERVER_STATUS_UPDATE_INTERVAL_SECONDS` | Poll interval in seconds (default `30`) |
| `SERVER_STATUS_ONLINE_TEXT` | Custom online text. Use `{status}` placeholder or leave blank for just the orb |
| `SERVER_STATUS_OFFLINE_TEXT` | Custom offline text |
| `PLAYER_COUNT_TEXT` | Custom player count text. Use `{players}` and `{max}` placeholders |

Leave a channel ID blank to disable that part. Leave a text value blank and the bot shows only the orb/count.

## Command roles

Add one or more Discord role IDs to gate every slash command:

```yaml
COMMAND_ROLES: "123456789012345678"
# or multiple: "123456789012345678,876543210987654321"
```

When set, the check runs in `CommandListener` before any command executes, so it applies uniformly to
`/configure`, `/ping`, `/register`, `/url`, and `/verify`. Members without a matching role get an ephemeral
"You do not have permission to use this command." reply.

## Compiling

Requirements: Maven, JDK 21, git.

```sh
git clone https://github.com/NamelessMC/Nameless-Java-API
cd Nameless-Java-API
mvn install
cd ..

git clone <this-repo>
cd Nameless-Link-Improved
mvn package
cd target
# find jar file here
```

## Requirements

- **Java 21 or newer**
- Build `Nameless-Java-API` first (`mvn install`), then `mvn package` here. `com.namelessmc:java-api:canary` is not on Maven Central and must be built from source.
- `GUILD_MEMBERS` intent must be enabled in the Discord Developer Portal.

## Configuration

All settings live in `config.yml` next to the jar. The bot creates a template on first run if the file is missing. Missing or invalid required values are reported on startup.

See the generated `config.yml` for the full list of keys and their defaults.

## Running as a systemd service (Linux)

Create `/etc/systemd/system/nameless-link.service`:

```ini
[Unit]
Description=Nameless-Link Improved Discord Bot
After=network.target

[Service]
Type=simple
User=nameless
WorkingDirectory=/opt/nameless-link
ExecStart=/usr/bin/java -jar /opt/nameless-link/Nameless-Link-Improved.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Then enable and start:

```sh
sudo systemctl daemon-reload
sudo systemctl enable nameless-link
sudo systemctl start nameless-link
sudo systemctl status nameless-link
```

Place the jar and `config.yml` in `/opt/nameless-link/` (or adjust `WorkingDirectory`/`ExecStart` accordingly). The bot reads `config.yml` from the working directory.

## Running on Windows

1. Install Java 21 (or newer) from [Adoptium](https://adoptium.net/) and ensure `java` is on your `PATH`.
2. Create a folder, e.g. `C:\nameless-link\`, and place `Nameless-Link-Improved.jar` and your `config.yml` in it.
3. Create a `start.bat` in that folder:

```bat
@echo off
cd /d "%~dp0"
java -jar Nameless-Link-Improved.jar
pause
```

4. Double-click `start.bat` to run the bot. The console window must stay open.

### Run on startup (optional)

Use Task Scheduler to start the bot on boot:

1. Open Task Scheduler → "Create Task".
2. General: name it `Nameless-Link`, select "Run whether user is logged on or not", check "Run with highest privileges".
3. Triggers: "At startup".
4. Actions: "Start a program" → Program: `java`, Arguments: `-jar Nameless-Link-Improved.jar`, Start in: `C:\nameless-link\`.
5. Conditions: uncheck "Start only if on AC power" if on a laptop.
6. Save and provide credentials when prompted.

Alternatively, use [NSSM](https://nssm.cc/) to wrap the jar as a Windows service.
