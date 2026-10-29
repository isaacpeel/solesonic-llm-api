package com.solesonic.model.session;

/**
 * Invites a user to a shared session with {@code agentName}, which must be one the deployment allows.
 * {@code openingMessage}, when given, is sent to the agent as the invitee once they accept, so the
 * session starts where the inviter meant it to; without one the session starts empty.
 */
public record SessionInviteRequest(String username, String agentName, String openingMessage) {
}
