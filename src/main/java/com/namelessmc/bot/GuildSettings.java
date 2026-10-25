package com.namelessmc.bot;

import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

/**
 * Resolves the default member role for a guild.
 *
 * <p>The role comes from the {@code DISCORD_ROLE_ID} environment variable, optionally limited to the guilds
 * listed in {@code DISCORD_ROLE_GUILD_IDS}. Nothing is stored: the value is read once at startup in
 * {@link Main#loadDefaultRoleConfig()} and is only ever consulted when a member joins.</p>
 */
public class GuildSettings {

	private static final Logger LOGGER = LoggerFactory.getLogger(GuildSettings.class);

	private final Guild guild;

	public GuildSettings(final Guild guild) {
		this.guild = guild;
	}

	/**
	 * The role id assigned to a member when they join this guild, or null when none is configured.
	 */
	public @Nullable Long getDefaultRoleId() {
		final Long envRoleId = Main.getDefaultRoleId();
		if (envRoleId == null) {
			return null;
		}

		if (!this.appliesToThisGuild()) {
			return null;
		}

		return envRoleId;
	}

	/**
	 * The role object for the configured default role, or null when it is not configured, was deleted, or is
	 * the {@code @everyone} role (which cannot be assigned to anyone).
	 */
	public @Nullable Role getDefaultRole() {
		final Long roleId = this.getDefaultRoleId();
		if (roleId == null) {
			return null;
		}

		final Role role = this.guild.getRoleById(roleId);
		if (role == null) {
			if (LOGGER.isWarnEnabled()) {
				LOGGER.warn("The configured DISCORD_ROLE_ID {} does not exist in guild '{}' ({}). No default role will be assigned. Check the id and make sure the role was not deleted.",
						roleId, this.guild.getName(), this.guild.getIdLong());
			}
			return null;
		}

		if (role.isPublicRole()) {
			if (LOGGER.isWarnEnabled()) {
				LOGGER.warn("The configured DISCORD_ROLE_ID {} is the @everyone role in guild '{}' ({}), which every member already has. No default role will be assigned.",
						roleId, this.guild.getName(), this.guild.getIdLong());
			}
			return null;
		}

		return role;
	}

	/**
	 * Whether the bot is allowed to assign the given role. A bot cannot assign a role that sits above its own
	 * highest role, or one it is not allowed to manage.
	 */
	public boolean canAssignRole(final Role role) {
		final boolean canAssign = this.guild.getSelfMember().canInteract(role);
		if (!canAssign && LOGGER.isWarnEnabled()) {
			LOGGER.warn("The bot cannot assign role '{}' ({}) in guild '{}' ({}). Move the bot's role above it in Server Settings > Roles, and make sure it has the 'Manage Roles' permission.",
					role.getName(), role.getIdLong(), this.guild.getName(), this.guild.getIdLong());
		}
		return canAssign;
	}

	/**
	 * The guild ids the default role is limited to, or an empty list when it applies to every guild.
	 */
	public List<Long> getDefaultRoleGuildIds() {
		return Main.getDefaultRoleGuildIds();
	}

	private boolean appliesToThisGuild() {
		final List<Long> configuredGuilds = Main.getDefaultRoleGuildIds();
		return configuredGuilds.isEmpty() || configuredGuilds.contains(this.guild.getIdLong());
	}

}
