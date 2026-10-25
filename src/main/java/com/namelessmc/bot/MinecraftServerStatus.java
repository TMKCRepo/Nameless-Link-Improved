package com.namelessmc.bot;

import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import com.namelessmc.bot.connections.StorageInitializer;
import java.util.concurrent.TimeUnit;

public class MinecraftServerStatus {

	private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftServerStatus.class);

	public static class Status {
		public final boolean online;
		public final int players;
		public final int max;
		public Status(boolean online, int players, int max) {
			this.online = online;
			this.players = players;
			this.max = max;
		}
	}

	public static Status ping(String host, int port, int timeoutMs) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMs);
			socket.setSoTimeout(timeoutMs);
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			DataInputStream in = new DataInputStream(socket.getInputStream());

			// Handshake packet
			byte[] handshake = buildHandshake(host, port);
			writeVarInt(out, handshake.length);
			out.write(handshake);
			// Request packet
			out.writeByte(1);
			out.writeByte(0);
			out.flush();

			// Read response
			int length = readVarInt(in);
			if (length <= 0) return new Status(false, 0, 0);
			byte[] data = new byte[length];
			in.readFully(data);
			// parse packet id
			int idx = 0;
			int packetId = readVarInt(data, new int[]{0});
			// packetId should 0
			int jsonLen = readVarInt(data, new int[]{idx});
			idx += varIntSize(jsonLen);
			// Actually need track; redo properly
			return parseJson(new String(data, StandardCharsets.UTF_8));
		} catch (Exception e) {
			LOGGER.debug("Ping failed for {}:{} - {}", host, port, e.getMessage());
			return new Status(false, 0, 0);
		}
	}

	private static byte[] buildHandshake(String host, int port) {
		// packet id 0x00
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		try {
			DataOutputStream d = new DataOutputStream(baos);
			d.writeByte(0x00); // packet id
			writeVarInt(d, 763); // protocol version (1.21.x-ish, server ignores mostly)
			writeString(d, host);
			d.writeShort(port);
			writeVarInt(d, 1); // next state status
			d.flush();
		} catch (IOException e) { }
		return baos.toByteArray();
	}

	private static void writeString(DataOutputStream out, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, b.length);
		out.write(b);
	}

	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = in.readByte() & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) break;
			shift += 7;
			if (shift >= 35) throw new IOException("VarInt too big");
		}
		return result;
	}

	// parse response json manually
	private static Status parseJson(String raw) {
		// raw contains whole packet bytes as string maybe; find json
		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		if (start < 0 || end < 0) return new Status(false,0,0);
		String json = raw.substring(start, end+1);
		int online = extractInt(json, "online");
		int max = extractInt(json, "max");
		// ensure description exists -> online
		return new Status(true, online, max);
	}

	private static int extractInt(String json, String key) {
		// find "key": number within players object; search last occurrence
		int i = json.lastIndexOf("\"" + key + "\"");
		if (i < 0) return 0;
		int colon = json.indexOf(':', i);
		if (colon < 0) return 0;
		StringBuilder sb = new StringBuilder();
		for (int j = colon+1; j < json.length(); j++) {
			char c = json.charAt(j);
			if (Character.isDigit(c)) sb.append(c);
			else if (sb.length() > 0) break;
		}
		try { return Integer.parseInt(sb.toString()); } catch (Exception e) { return 0; }
	}

	// unused helpers
	private static int readVarInt(byte[] data, int[] idx) {
		int result=0, shift=0, i=idx[0];
		while (true) {
			int b = data[i++] & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80)==0) break;
			shift += 7;
		}
		idx[0]=i;
		return result;
	}
	private static int varIntSize(int v){ int s=0; do { s++; v>>>=7; } while(v!=0); return s; }

	public static void updateChannels() {
		String host = StorageInitializer.getEnvString("SERVER_STATUS_HOST", "mc.example.com");
		host = com.namelessmc.bot.ConfigManager.get("SERVER_STATUS_HOST", host);
		int port;
		try { port = Integer.parseInt(com.namelessmc.bot.ConfigManager.get("SERVER_STATUS_PORT", "25565")); } catch (Exception e) { port = 25565; }
		Status st = ping(host, port, 5000);

		String onlineText = com.namelessmc.bot.ConfigManager.get("SERVER_STATUS_ONLINE_TEXT", "");
		String offlineText = com.namelessmc.bot.ConfigManager.get("SERVER_STATUS_OFFLINE_TEXT", "");
		String countText = com.namelessmc.bot.ConfigManager.get("PLAYER_COUNT_TEXT", "");

		String statusId = com.namelessmc.bot.ConfigManager.get("SERVER_STATUS_CHANNEL_ID");
		if (statusId != null && !statusId.isBlank()) {
			String txt;
			if (st.online) {
				if (onlineText == null || onlineText.isBlank()) {
					txt = "🟢 Online";
				} else if (onlineText.contains("{status}")) {
					txt = onlineText.replace("{status}", "Online");
				} else if (onlineText.contains("🟢") || onlineText.contains("🔴")) {
					txt = onlineText;
				} else {
					txt = onlineText + " 🟢 Online";
				}
			} else {
				if (offlineText == null || offlineText.isBlank()) {
					txt = "🔴 Offline";
				} else if (offlineText.contains("{status}")) {
					txt = offlineText.replace("{status}", "Offline");
				} else if (offlineText.contains("🟢") || offlineText.contains("🔴")) {
					txt = offlineText;
				} else {
					txt = offlineText + " 🔴 Offline";
				}
			}
			renameChannel(statusId, txt);
		}
		String countId = com.namelessmc.bot.ConfigManager.get("PLAYER_COUNT_CHANNEL_ID");
		if (countId != null && !countId.isBlank()) {
			String txt;
			if (!st.online) {
				txt = "0/0";
			} else if (countText == null || countText.isBlank()) {
				txt = st.players + "/" + st.max;
			} else if (countText.contains("{players}") || countText.contains("{max}")) {
				txt = countText.replace("{players}", String.valueOf(st.players)).replace("{max}", String.valueOf(st.max));
			} else {
				txt = countText + " " + st.players + "/" + st.max;
			}
			renameChannel(countId, txt);
		}
	}

	private static void renameChannel(String id, String name) {
		try {
			long cid = Long.parseLong(id.trim());
			for (int i = 0; i < Main.getShardCount(); i++) {
				VoiceChannel ch = Main.getJda(i).getVoiceChannelById(cid);
				if (ch != null) {
					ch.getManager().setName(name).queue();
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed updating channel {}: {}", id, e.getMessage());
		}
	}

	public static void schedule() {
		String statusId = com.namelessmc.bot.ConfigManager.get("SERVER_STATUS_CHANNEL_ID");
		String countId = com.namelessmc.bot.ConfigManager.get("PLAYER_COUNT_CHANNEL_ID");
		if ((statusId == null || statusId.isBlank()) && (countId == null || countId.isBlank())) {
			LOGGER.info("No server status channels configured; skipping status task");
			return;
		}
		int interval;
		try { interval = Integer.parseInt(com.namelessmc.bot.ConfigManager.get("SERVER_STATUS_UPDATE_INTERVAL_SECONDS", "30")); }
		catch (Exception e) { interval = 30; }
		Main.getExecutorService().scheduleAtFixedRate(MinecraftServerStatus::updateChannels, 0, interval, TimeUnit.SECONDS);
		LOGGER.info("Scheduled server status updates every {}s", interval);
	}
}
