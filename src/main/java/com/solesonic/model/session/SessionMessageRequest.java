package com.solesonic.model.session;

import java.util.Set;
import java.util.UUID;

/**
 * A typed message, or an answer to the session's open question (agent mode). In a conversation the text
 * may be empty when the message carries attachments or a command.
 *
 * @param attachmentIds the sender's own staged uploads (conversation mode only), as the private chat sends them
 * @param commands      the slash commands the message runs, by name without the slash (conversation mode only)
 */
public record SessionMessageRequest(String text, SessionPromptAnswer promptAnswer, Set<UUID> attachmentIds, Set<String> commands) {
}
