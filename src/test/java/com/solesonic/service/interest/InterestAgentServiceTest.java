package com.solesonic.service.interest;

import com.solesonic.model.interest.AgentCadence;
import com.solesonic.model.interest.AgentStatus;
import com.solesonic.model.interest.AgentType;
import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.InterestAgentSummary;
import com.solesonic.model.interest.InterestAgentUpdateRequest;
import com.solesonic.model.interest.InterestErrorCode;
import com.solesonic.repository.interest.InterestAgentRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.service.interest.InterestOfferServiceTest.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterestAgentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final ZonedDateTime NOW_ZONED = NOW.atZone(ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private InterestAgentRepository interestAgentRepository;

    @Mock
    private UserPreferencesService userPreferencesService;

    private InterestAgentService interestAgentService;

    @BeforeEach
    void setUp() {
        interestAgentService = new InterestAgentService(interestAgentRepository, userPreferencesService, InterestSettingsFixture.settings(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(userPreferencesService.getZone(USER_ID)).thenReturn(ZoneOffset.UTC);

        when(interestAgentRepository.save(any(InterestAgent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void updatesOnlyTheFieldsGiven() {
        InterestAgent agent = agent(AgentStatus.ACTIVE);
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        InterestAgentSummary summary = interestAgentService.update(USER_ID, agent.getId(),
                new InterestAgentUpdateRequest("  F1\n ", null, AgentCadence.WEEKLY, null));

        assertThat(summary.label()).isEqualTo("F1");
        assertThat(summary.query()).isEqualTo("Formula 1 race results");
        assertThat(summary.cadence()).isEqualTo(AgentCadence.WEEKLY);
        assertThat(summary.status()).isEqualTo(AgentStatus.ACTIVE);
    }

    @Test
    void changingTheCadenceSchedulesTheNextRunFromTheLastOneAtTheNewCadence() {
        InterestAgent agent = agent(AgentStatus.ACTIVE);
        agent.setCadence(AgentCadence.WEEKLY);
        agent.setLastRunAt(NOW_ZONED.withHour(InterestSettingsFixture.DELIVERY_HOUR));
        agent.setNextRunAt(NOW_ZONED.withHour(InterestSettingsFixture.DELIVERY_HOUR).plusDays(7));
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        interestAgentService.update(USER_ID, agent.getId(), new InterestAgentUpdateRequest(null, null, AgentCadence.DAILY, null));

        assertThat(agent.getNextRunAt()).isEqualTo(NOW_ZONED.withHour(InterestSettingsFixture.DELIVERY_HOUR).plusDays(1));
    }

    @Test
    void changingTheCadenceMakesTheAgentDueNowWhenItsNewNextRunIsAlreadyPast() {
        InterestAgent agent = agent(AgentStatus.ACTIVE);
        agent.setCadence(AgentCadence.WEEKLY);
        agent.setLastRunAt(NOW_ZONED.minusDays(3));
        agent.setNextRunAt(NOW_ZONED.plusDays(4));
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        interestAgentService.update(USER_ID, agent.getId(), new InterestAgentUpdateRequest(null, null, AgentCadence.DAILY, null));

        assertThat(agent.getNextRunAt()).isEqualTo(NOW_ZONED);
    }

    @Test
    void resumingResetsFailuresAndMakesTheAgentDueNow() {
        InterestAgent agent = agent(AgentStatus.PAUSED);
        agent.setFailures(5);
        agent.setNextRunAt(NOW_ZONED.plusDays(3));
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        interestAgentService.update(USER_ID, agent.getId(), new InterestAgentUpdateRequest(null, null, null, AgentStatus.ACTIVE));

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(agent.getFailures()).isZero();
        assertThat(agent.getNextRunAt()).isEqualTo(NOW_ZONED);
    }

    @Test
    void refusesAnEmptyBlankOrOversizedEditBeforeLookingTheAgentUp() {
        UUID agentId = UUID.randomUUID();

        assertError(() -> interestAgentService.update(USER_ID, agentId, new InterestAgentUpdateRequest(null, null, null, null)),
                InterestErrorCode.INVALID_AGENT_UPDATE);
        assertError(() -> interestAgentService.update(USER_ID, agentId, new InterestAgentUpdateRequest(" \n ", null, null, null)),
                InterestErrorCode.INVALID_AGENT_UPDATE);
        assertError(() -> interestAgentService.update(USER_ID, agentId, new InterestAgentUpdateRequest(null, "q".repeat(401), null, null)),
                InterestErrorCode.INVALID_AGENT_UPDATE);

        verify(interestAgentRepository, never()).lockByIdAndUserId(any(), any());
    }

    @Test
    void anotherUsersAgentIsNotFoundForEveryOperation() {
        UUID agentId = UUID.randomUUID();
        when(interestAgentRepository.lockByIdAndUserId(agentId, USER_ID)).thenReturn(Optional.empty());

        assertError(() -> interestAgentService.update(USER_ID, agentId, new InterestAgentUpdateRequest("x", null, null, null)),
                InterestErrorCode.AGENT_NOT_FOUND);
        assertError(() -> interestAgentService.delete(USER_ID, agentId), InterestErrorCode.AGENT_NOT_FOUND);
        assertError(() -> interestAgentService.runNow(USER_ID, agentId), InterestErrorCode.AGENT_NOT_FOUND);

        verify(interestAgentRepository, never()).delete(any());
        verify(interestAgentRepository, never()).save(any());
    }

    @Test
    void deletesAnOwnedAgent() {
        InterestAgent agent = agent(AgentStatus.ACTIVE);
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        interestAgentService.delete(USER_ID, agent.getId());

        verify(interestAgentRepository).delete(agent);
    }

    @Test
    void runNowMakesTheAgentDue() {
        InterestAgent agent = agent(AgentStatus.ACTIVE);
        agent.setLastRunAt(NOW_ZONED.minus(InterestSettingsFixture.MANUAL_RUN_COOLDOWN).minusSeconds(1));
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        interestAgentService.runNow(USER_ID, agent.getId());

        assertThat(agent.getNextRunAt()).isEqualTo(NOW_ZONED);
    }

    @Test
    void runNowInsideTheCooldownIsTooSoon() {
        InterestAgent agent = agent(AgentStatus.ACTIVE);
        agent.setLastRunAt(NOW_ZONED.minusMinutes(1));
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        assertError(() -> interestAgentService.runNow(USER_ID, agent.getId()), InterestErrorCode.RUN_TOO_SOON);

        verify(interestAgentRepository, never()).save(any());
    }

    @Test
    void runNowOnAPausedAgentIsRefused() {
        InterestAgent agent = agent(AgentStatus.PAUSED);
        when(interestAgentRepository.lockByIdAndUserId(agent.getId(), USER_ID)).thenReturn(Optional.of(agent));

        assertError(() -> interestAgentService.runNow(USER_ID, agent.getId()), InterestErrorCode.INVALID_AGENT_UPDATE);
    }

    static InterestAgent agent(AgentStatus status) {
        InterestAgent agent = new InterestAgent();
        agent.setId(UUID.randomUUID());
        agent.setUserId(USER_ID);
        agent.setAgentType(AgentType.NEWS);
        agent.setTopicKey("sports.formula-1");
        agent.setLabel("Formula 1");
        agent.setQuery("Formula 1 race results");
        agent.setCadence(AgentCadence.DAILY);
        agent.setStatus(status);
        agent.setNextRunAt(NOW_ZONED.plusHours(1));
        agent.setCreated(NOW_ZONED.minusDays(10));
        agent.setUpdated(NOW_ZONED.minusDays(10));
        return agent;
    }
}
