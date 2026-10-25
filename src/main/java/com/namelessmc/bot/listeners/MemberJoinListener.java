package com.namelessmc.bot.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.namelessmc.bot.GuildSettings;
import com.namelessmc.bot.Main;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Assigns the configured default role to members when they join a guild.
 *
 * <p>The role comes from the {@code DISCORD_ROLE_ID} environment variable, optionally restricted to specific
 * guilds with {@code DISCORD_ROLE_GUILD_IDS}. See {@link GuildSettings#getDefaultRole()}.
 *
 * <p>Only the join event is handled, so members who were already in the guild are never touched.</p>
 */
public class MemberJoinListener extends ListenerAdapter {

	private static final Logger LOGGER = LoggerFactory.getLogger(MemberJoinListener.class);

	@Override
	public void onGuildMemberJoin(final GuildMemberJoinEvent event) {
		final Guild guild = event.getGuild();
		final Member member = event.getMember();

		final GuildSettings settings = Main.getGuildSettings(guild);

		final Role role = settings.getDefaultRole();
		if (role == null) {
			return;
		}

		if (member.getUser().isBot()) {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("Skipping default role in guild {}, user {} is a bot.", guild.getIdLong(), member.getIdLong());
			}
			return;
		}

		if (member.getRoles().contains(role)) {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("Member {} already has default role {} in guild {}", member.getIdLong(), role.getIdLong(), guild.getIdLong());
			}
			return;
		}

		guild.addRoleToMember(member, role).queue(
				success -> LOGGER.info("Assigned default role '{}' ({}) to new member '{}' ({}) in guild '{}' ({}).",
						role.getName(), role.getIdLong(), member.getUser().getName(), member.getIdLong(),
						guild.getName(), guild.getIdLong()),
				error -> {
					if (error instanceof HierarchyException) {
						LOGGER.warn("Cannot assign default role '{}' ({}) to '{}' in guild '{}': the role is not below the bot's highest role.",
								role.getName(), role.getIdLong(), member.getUser().getName(), guild.getIdLong());
					} else if (error instanceof InsufficientPermissionException) {
						LOGGER.warn("Cannot assign default role '{}' ({}) to '{}' in guild '{}': the bot is missing the Manage Roles permission.",
								role.getName(), role.getIdLong(), member.getUser().getName(), guild.getIdLong());
					} else {
						LOGGER.error("Failed to assign default role '{}' ({}) to '{}' in guild '{}'.",
								role.getName(), role.getIdLong(), member.getUser().getName(), guild.getIdLong(), error);
					}
				});
	}

}
