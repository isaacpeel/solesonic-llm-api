package com.solesonic.service.interest;

import com.solesonic.exception.interest.InterestException;
import com.solesonic.model.interest.InterestUpdateSummary;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestUpdateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The news chats an agent created. Built on repositories alone, because {@code ChatService.delete}
 * calls it.
 */
@Service
public class InterestUpdateService {
    private final InterestUpdateRepository interestUpdateRepository;
    private final InterestAgentRepository interestAgentRepository;

    public InterestUpdateService(InterestUpdateRepository interestUpdateRepository, InterestAgentRepository interestAgentRepository) {
        this.interestUpdateRepository = interestUpdateRepository;
        this.interestAgentRepository = interestAgentRepository;
    }

    @Transactional(readOnly = true)
    public Page<InterestUpdateSummary> updates(UUID userId, UUID agentId, Pageable pageable) {
        if (interestAgentRepository.findByIdAndUserId(agentId, userId).isEmpty()) {
            throw InterestException.agentNotFound();
        }

        //The order is the query's own; a client-supplied sort could only name a property that does not exist.
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        return interestUpdateRepository.findByUserIdAndAgentIdOrderByCreatedDesc(userId, agentId, unsorted)
                .map(InterestUpdateSummary::of);
    }

    /**
     * How a chat screen knows it is showing news.
     */
    @Transactional(readOnly = true)
    public InterestUpdateSummary byChat(UUID userId, UUID chatId) {
        return interestUpdateRepository.findByChatIdAndUserId(chatId, userId)
                .map(InterestUpdateSummary::of)
                .orElseThrow(InterestException::updateNotFound);
    }

    /**
     * Joins the transaction deleting the chat. The agent is untouched.
     */
    @Transactional
    public void deleteForChat(UUID chatId, UUID userId) {
        interestUpdateRepository.deleteByChatIdAndUserId(chatId, userId);
    }
}
