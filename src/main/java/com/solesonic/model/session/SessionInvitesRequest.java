package com.solesonic.model.session;

import java.util.List;

/**
 * People the owner of a conversation session adds later, by exact username.
 */
public record SessionInvitesRequest(List<String> usernames) {
}
