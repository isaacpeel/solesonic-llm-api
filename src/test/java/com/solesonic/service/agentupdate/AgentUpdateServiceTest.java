package com.solesonic.service.agentupdate;

import com.solesonic.exception.agentupdate.AgentUpdateException;
import com.solesonic.model.agentupdate.AgentUpdate;
import com.solesonic.model.agentupdate.AgentUpdateErrorCode;
import com.solesonic.model.agentupdate.AgentUpdateRequest;
import com.solesonic.model.agentupdate.AgentUpdateResponse;
import com.solesonic.model.chat.group.ChatGroup;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.push.PushEvent;
import com.solesonic.model.user.UserProfile;
import com.solesonic.repository.agentupdate.AgentUpdateRepository;
import com.solesonic.repository.chat.ChatGroupRepository;
import com.solesonic.service.a2a.A2AStickyAgentService;
import com.solesonic.service.interest.InterestChatWriter;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.user.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentUpdateServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final String KEY = "jobs:alert:1234";

    @Mock
    private AgentUpdateRepository agentUpdateRepository;
    @Mock
    private ChatGroupRepository chatGroupRepository;
    @Mock
    private InterestChatWriter chatWriter;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private PushNotificationService pushNotificationService;
    @Mock
    private A2AStickyAgentService stickyAgentService;

    private AgentUpdateService service;
    private AgentUpdateWriter writer;

    @BeforeEach
    void setUp() {
        writer = org.mockito.Mockito.spy(new AgentUpdateWriter(agentUpdateRepository, chatGroupRepository, chatWriter));
        service = new AgentUpdateService(agentUpdateRepository, writer, userProfileService,
                pushNotificationService, stickyAgentService, Clock.fixed(NOW, ZoneOffset.UTC), Set.of("jobs"), 10);
        when(agentUpdateRepository.findByAgentAndIdempotencyKey("jobs", KEY)).thenReturn(Optional.empty());
        when(userProfileService.profile(USER_ID)).thenReturn(Optional.of(new UserProfile(USER_ID, "alex", null, null)));
        when(chatGroupRepository.findByUserId(USER_ID)).thenReturn(List.of());
        when(chatGroupRepository.save(any(ChatGroup.class))).thenAnswer(invocation -> {
            ChatGroup group = invocation.getArgument(0);
            group.setId(GROUP_ID);
            return group;
        });
        when(chatWriter.write(eq(USER_ID), anyString(), any(), anyString(), any())).thenAnswer(invocation -> {
            Chat chat = new Chat();
            chat.setId(CHAT_ID);
            return chat;
        });
        when(agentUpdateRepository.saveAndFlush(any(AgentUpdate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stickyAgentService.activate(any(), anyString())).thenReturn(Mono.empty());
    }

    private static AgentUpdateRequest request(Boolean pinAgent) {
        return new AgentUpdateRequest(USER_ID, "jobs", "3 new jobs", "## Startups\n- [Data Engineer](https://acme.example/jobs/1)",
                KEY, null, pinAgent);
    }

    @Test
    void writesAChatInTheJobsGroupPushesAndPinsTheAgent() {
        AgentUpdateResponse response = service.deliver(request(true));

        assertThat(response.chatId()).isEqualTo(CHAT_ID);
        assertThat(response.duplicate()).isFalse();

        ArgumentCaptor<ChatGroup> group = ArgumentCaptor.forClass(ChatGroup.class);
        verify(chatGroupRepository).save(group.capture());
        assertThat(group.getValue().getName()).isEqualTo("Jobs");
        assertThat(group.getValue().getUserId()).isEqualTo(USER_ID);

        verify(chatWriter).write(eq(USER_ID), eq("3 new jobs"), eq(GROUP_ID),
                eq("## Startups\n- [Data Engineer](https://acme.example/jobs/1)"), any(ZonedDateTime.class));
        verify(stickyAgentService).activate(CHAT_ID, "jobs");
        verify(pushNotificationService).push(eq(USER_ID), eq(new PushEvent.AgentUpdated(CHAT_ID, "jobs", response.updateId())));
    }

    @Test
    void reusesTheUsersExistingGroupAndDoesNotPinUnlessAsked() {
        ChatGroup jobs = new ChatGroup();
        jobs.setId(GROUP_ID);
        jobs.setName("jobs");
        when(chatGroupRepository.findByUserId(USER_ID)).thenReturn(List.of(jobs));

        service.deliver(request(null));

        verify(chatGroupRepository, never()).save(any());
        verify(chatWriter).write(eq(USER_ID), anyString(), eq(GROUP_ID), anyString(), any());
        verify(stickyAgentService, never()).activate(any(), anyString());
    }

    @Test
    void aRepeatedKeyReturnsTheFirstChatAndPushesNothing() {
        AgentUpdate first = new AgentUpdate();
        first.setId(UUID.randomUUID());
        first.setUserId(USER_ID);
        first.setChatId(CHAT_ID);
        when(agentUpdateRepository.findByAgentAndIdempotencyKey("jobs", KEY)).thenReturn(Optional.of(first));

        AgentUpdateResponse response = service.deliver(request(true));

        assertThat(response).isEqualTo(new AgentUpdateResponse(CHAT_ID, first.getId(), true));
        verify(chatWriter, never()).write(any(), any(), any(), any(), any());
        verify(pushNotificationService, never()).push(any(), any());
    }

    @Test
    void anotherUsersKeyIsRefusedRatherThanAnsweredWithTheirChat() {
        AgentUpdate first = new AgentUpdate();
        first.setId(UUID.randomUUID());
        first.setUserId(UUID.randomUUID());
        first.setChatId(UUID.randomUUID());
        when(agentUpdateRepository.findByAgentAndIdempotencyKey("jobs", KEY)).thenReturn(Optional.of(first));

        assertError(() -> service.deliver(request(false)), AgentUpdateErrorCode.INVALID_UPDATE);
    }

    @Test
    void aRaceOnTheKeyAnswersWithTheWinner() {
        AgentUpdate winner = new AgentUpdate();
        winner.setId(UUID.randomUUID());
        winner.setUserId(USER_ID);
        winner.setChatId(CHAT_ID);
        when(agentUpdateRepository.findByAgentAndIdempotencyKey("jobs", KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate key")).when(writer)
                .write(any(), any(), any(), any(), any(), any(), any());

        assertThat(service.deliver(request(false)).duplicate()).isTrue();
        verify(pushNotificationService, never()).push(any(), any());
    }

    @Test
    void anUnknownUserIs404() {
        when(userProfileService.profile(USER_ID)).thenReturn(Optional.empty());

        assertError(() -> service.deliver(request(false)), AgentUpdateErrorCode.USER_NOT_FOUND);
    }

    @Test
    void theDailyLimitIsPerUser() {
        when(agentUpdateRepository.countByUserIdAndCreatedAfter(eq(USER_ID), any())).thenReturn(10L);

        assertError(() -> service.deliver(request(false)), AgentUpdateErrorCode.RATE_LIMITED);
        verify(chatWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void onlyAllowlistedAgentsMayDeliver() {
        assertError(() -> service.deliver(new AgentUpdateRequest(USER_ID, "feud", "t", "m", KEY, null, false)),
                AgentUpdateErrorCode.UNKNOWN_AGENT);
    }

    @Test
    void refusesMissingOrOversizedFields() {
        assertError(() -> service.deliver(new AgentUpdateRequest(null, "jobs", "t", "m", KEY, null, false)), AgentUpdateErrorCode.INVALID_UPDATE);
        assertError(() -> service.deliver(new AgentUpdateRequest(USER_ID, "jobs", "t", "m", "short", null, false)), AgentUpdateErrorCode.INVALID_UPDATE);
        assertError(() -> service.deliver(new AgentUpdateRequest(USER_ID, "jobs", "t", "x".repeat(16 * 1024 + 1), KEY, null, false)),
                AgentUpdateErrorCode.INVALID_UPDATE);
        assertError(() -> service.deliver(new AgentUpdateRequest(USER_ID, "jobs", "", "m", KEY, null, false)), AgentUpdateErrorCode.INVALID_UPDATE);
        assertError(() -> service.deliver(new AgentUpdateRequest(USER_ID, "jobs", "t", "<img src=x>", KEY, null, false)),
                AgentUpdateErrorCode.INVALID_UPDATE);
    }

    private static void assertError(Runnable call, AgentUpdateErrorCode errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(AgentUpdateException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
