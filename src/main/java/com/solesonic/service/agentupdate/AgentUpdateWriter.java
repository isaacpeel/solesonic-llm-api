package com.solesonic.service.agentupdate;

import com.solesonic.model.agentupdate.AgentUpdate;
import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.agentupdate.AgentUpdateRepository;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.service.interest.InterestChatWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * The chat, its message, the group (created the first time) and the update row, in one transaction: they exist
 * together or not at all. A second delivery of the same key fails the unique constraint here, on flush.
 */
@Component
public class AgentUpdateWriter {
    private final AgentUpdateRepository agentUpdateRepository;
    private final ChatGroupRepository chatGroupRepository;
    private final InterestChatWriter chatWriter;

    public AgentUpdateWriter(AgentUpdateRepository agentUpdateRepository,
                             ChatGroupRepository chatGroupRepository,
                             InterestChatWriter chatWriter) {
        this.agentUpdateRepository = agentUpdateRepository;
        this.chatGroupRepository = chatGroupRepository;
        this.chatWriter = chatWriter;
    }

    @Transactional
    public AgentUpdate write(UUID userId, String agent, String idempotencyKey, String groupName, String title,
                             String markdown, ZonedDateTime now) {
        UUID chatGroupId = chatGroup(userId, groupName, now);
        Chat chat = chatWriter.write(userId, title, chatGroupId, markdown, now);

        AgentUpdate agentUpdate = new AgentUpdate();
        agentUpdate.setId(UUID.randomUUID());
        agentUpdate.setUserId(userId);
        agentUpdate.setAgent(agent);
        agentUpdate.setIdempotencyKey(idempotencyKey);
        agentUpdate.setChatId(chat.getId());
        agentUpdate.setCreated(now);

        return agentUpdateRepository.saveAndFlush(agentUpdate);
    }

    /**
     * The user's group by that name, created the first time.
     */
    private UUID chatGroup(UUID userId, String groupName, ZonedDateTime now) {
        return chatGroupRepository.findByUserId(userId).stream()
                .filter(chatGroup -> groupName.equalsIgnoreCase(chatGroup.getName()))
                .findFirst()
                .map(ChatGroup::getId)
                .orElseGet(() -> {
                    ChatGroup chatGroup = new ChatGroup();
                    chatGroup.setUserId(userId);
                    chatGroup.setName(groupName);
                    chatGroup.setTimestamp(now);
                    return chatGroupRepository.save(chatGroup).getId();
                });
    }
}
