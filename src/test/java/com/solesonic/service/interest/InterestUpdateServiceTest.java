package com.solesonic.service.interest;

import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.InterestErrorCode;
import com.solesonic.model.interest.InterestUpdate;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.repository.interest.InterestUpdateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.interest.InterestOfferServiceTest.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterestUpdateServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();

    @Mock
    private InterestUpdateRepository interestUpdateRepository;

    @Mock
    private InterestAgentRepository interestAgentRepository;

    private InterestUpdateService interestUpdateService;

    @BeforeEach
    void setUp() {
        interestUpdateService = new InterestUpdateService(interestUpdateRepository, interestAgentRepository);
    }

    @Test
    void listsAnOwnedAgentsUpdates() {
        UUID agentId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        InterestUpdate update = update(agentId);

        when(interestAgentRepository.findByIdAndUserId(agentId, USER_ID)).thenReturn(Optional.of(new InterestAgent()));
        when(interestUpdateRepository.findByUserIdAndAgentIdOrderByCreatedDesc(USER_ID, agentId, pageable))
                .thenReturn(new PageImpl<>(List.of(update)));

        assertThat(interestUpdateService.updates(USER_ID, agentId, pageable).getContent()).singleElement()
                .satisfies(summary -> assertThat(summary.chatId()).isEqualTo(CHAT_ID));
    }

    @Test
    void anotherUsersAgentsUpdatesAreNotFound() {
        UUID agentId = UUID.randomUUID();
        when(interestAgentRepository.findByIdAndUserId(agentId, USER_ID)).thenReturn(Optional.empty());

        assertError(() -> interestUpdateService.updates(USER_ID, agentId, PageRequest.of(0, 20)), InterestErrorCode.AGENT_NOT_FOUND);

        verify(interestUpdateRepository, never()).findByUserIdAndAgentIdOrderByCreatedDesc(any(), any(), any());
    }

    @Test
    void aChatThatIsNotNewsIsNotFound() {
        when(interestUpdateRepository.findByChatIdAndUserId(CHAT_ID, USER_ID)).thenReturn(Optional.empty());

        assertError(() -> interestUpdateService.byChat(USER_ID, CHAT_ID), InterestErrorCode.UPDATE_NOT_FOUND);
    }

    @Test
    void deletingANewsChatDeletesOnlyTheCallersUpdateRow() {
        interestUpdateService.deleteForChat(CHAT_ID, USER_ID);

        verify(interestUpdateRepository).deleteByChatIdAndUserId(CHAT_ID, USER_ID);
        verify(interestUpdateRepository, never()).deleteByChatIdAndUserId(CHAT_ID, OTHER_USER_ID);
    }

    private static InterestUpdate update(UUID agentId) {
        InterestUpdate update = new InterestUpdate();
        update.setId(UUID.randomUUID());
        update.setUserId(USER_ID);
        update.setAgentId(agentId);
        update.setLabel("Formula 1");
        update.setChatId(CHAT_ID);
        update.setItemCount(3);
        update.setCreated(ZonedDateTime.now());
        return update;
    }
}
