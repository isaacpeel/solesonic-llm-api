package com.solesonic.service.memory;

import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.memory.UserMemory;
import com.solesonic.model.memory.UserMemoryEvidence;

import java.util.List;
import java.util.UUID;

/**
 * Everything one reflection is allowed to see: one owner, one of that owner's conversations, the
 * window of it being read, and that owner's own live memories with their evidence.
 *
 * @param window the messages shown to the model, already narrowed to {@code USER} and
 *               {@code ASSISTANT} rows of {@code chatId}
 */
public record ReflectionContext(UUID userId,
                                UUID chatId,
                                List<ChatMessage> window,
                                List<UserMemory> memories,
                                List<UserMemoryEvidence> evidence) {
}
