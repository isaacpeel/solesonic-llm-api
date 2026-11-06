package com.solesonic.api.interest;

import com.solesonic.exception.handler.InterestExceptionHandler;
import com.solesonic.exception.interest.InterestException;
import com.solesonic.model.interest.AgentCadence;
import com.solesonic.model.interest.AgentStatus;
import com.solesonic.model.interest.AgentType;
import com.solesonic.model.interest.InterestAgentSummary;
import com.solesonic.model.interest.InterestOfferSummary;
import com.solesonic.model.interest.OfferStatus;
import com.solesonic.service.interest.InterestAgentService;
import com.solesonic.service.interest.InterestOfferService;
import com.solesonic.service.interest.InterestUpdateService;
import com.solesonic.service.security.ResourceOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterestControllerTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID OFFER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();

    @Mock
    private InterestOfferService interestOfferService;

    @Mock
    private InterestAgentService interestAgentService;

    @Mock
    private InterestUpdateService interestUpdateService;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InterestController(interestOfferService, interestAgentService, interestUpdateService, resourceOwnershipService))
                .setControllerAdvice(new InterestExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();

        when(resourceOwnershipService.isOwner(eq(USER_ID), any())).thenReturn(true);
        when(resourceOwnershipService.isOwner(eq(OTHER_USER_ID), any())).thenReturn(false);
    }

    /**
     * The offer screen shows what would be watched and why, never how it was found.
     */
    @Test
    void anOfferNeverExposesItsQueryTopicKeyOrSourceMemories() throws Exception {
        when(interestOfferService.get(USER_ID, OFFER_ID)).thenReturn(new InterestOfferSummary(OFFER_ID, AgentType.NEWS, "Formula 1",
                "You follow Formula 1", OfferStatus.PENDING, ZonedDateTime.now(), ZonedDateTime.now().plusDays(7)));

        mockMvc.perform(get("/users/{userId}/interest-offers/{offerId}", USER_ID, OFFER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Formula 1"))
                .andExpect(jsonPath("$.query").doesNotExist())
                .andExpect(jsonPath("$.topicKey").doesNotExist())
                .andExpect(jsonPath("$.sourceMemoryIds").doesNotExist());
    }

    @Test
    void acceptAnswersCreatedWithTheAgent() throws Exception {
        when(interestOfferService.accept(eq(USER_ID), eq(OFFER_ID), any())).thenReturn(agentSummary());

        mockMvc.perform(post("/users/{userId}/interest-offers/{offerId}/accept", USER_ID, OFFER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cadence\":\"WEEKLY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(AGENT_ID.toString()));
    }

    @Test
    void acceptWithoutABodyIsAllowed() throws Exception {
        when(interestOfferService.accept(USER_ID, OFFER_ID, null)).thenReturn(agentSummary());

        mockMvc.perform(post("/users/{userId}/interest-offers/{offerId}/accept", USER_ID, OFFER_ID))
                .andExpect(status().isCreated());
    }

    @Test
    void refusalsLeaveWithTheirOwnStatusAndCode() throws Exception {
        assertRefusal(InterestException.offerAlreadyDecided(), 409, "OFFER_ALREADY_DECIDED");
        assertRefusal(InterestException.offerExpired(), 409, "OFFER_EXPIRED");
        assertRefusal(InterestException.agentLimitReached(), 409, "AGENT_LIMIT_REACHED");
        assertRefusal(InterestException.offerWithdrawn(), 410, "OFFER_WITHDRAWN");
        assertRefusal(InterestException.notPermitted(), 403, "NOT_PERMITTED");
        assertRefusal(InterestException.offerNotFound(), 404, "OFFER_NOT_FOUND");
    }

    @Test
    void declineAnswersNoContent() throws Exception {
        mockMvc.perform(post("/users/{userId}/interest-offers/{offerId}/decline", USER_ID, OFFER_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void runAnswersAcceptedOrTooManyRequests() throws Exception {
        when(interestAgentService.runNow(USER_ID, AGENT_ID)).thenReturn(agentSummary());

        mockMvc.perform(post("/users/{userId}/interest-agents/{agentId}/run", USER_ID, AGENT_ID))
                .andExpect(status().isAccepted());

        when(interestAgentService.runNow(USER_ID, AGENT_ID)).thenThrow(InterestException.runTooSoon());

        mockMvc.perform(post("/users/{userId}/interest-agents/{agentId}/run", USER_ID, AGENT_ID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RUN_TOO_SOON"));
    }

    @Test
    void anInvalidEditIsABadRequest() throws Exception {
        when(interestAgentService.update(eq(USER_ID), eq(AGENT_ID), any())).thenThrow(InterestException.invalidAgentUpdate("Nothing to change."));

        mockMvc.perform(patch("/users/{userId}/interest-agents/{agentId}", USER_ID, AGENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_UPDATE"));
    }

    @Test
    void anotherUsersPathIsNotFoundOnEveryEndpointAndReachesNoService() throws Exception {
        mockMvc.perform(get("/users/{userId}/interest-offers", OTHER_USER_ID)).andExpect(status().isNotFound());
        mockMvc.perform(get("/users/{userId}/interest-offers/{offerId}", OTHER_USER_ID, OFFER_ID)).andExpect(status().isNotFound());
        mockMvc.perform(post("/users/{userId}/interest-offers/{offerId}/accept", OTHER_USER_ID, OFFER_ID)).andExpect(status().isNotFound());
        mockMvc.perform(post("/users/{userId}/interest-offers/{offerId}/decline", OTHER_USER_ID, OFFER_ID)).andExpect(status().isNotFound());
        mockMvc.perform(get("/users/{userId}/interest-agents", OTHER_USER_ID)).andExpect(status().isNotFound());
        mockMvc.perform(patch("/users/{userId}/interest-agents/{agentId}", OTHER_USER_ID, AGENT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\"}")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/users/{userId}/interest-agents/{agentId}", OTHER_USER_ID, AGENT_ID)).andExpect(status().isNotFound());
        mockMvc.perform(post("/users/{userId}/interest-agents/{agentId}/run", OTHER_USER_ID, AGENT_ID)).andExpect(status().isNotFound());
        mockMvc.perform(get("/users/{userId}/interest-agents/{agentId}/updates", OTHER_USER_ID, AGENT_ID)).andExpect(status().isNotFound());
        mockMvc.perform(get("/users/{userId}/interest-updates/by-chat/{chatId}", OTHER_USER_ID, CHAT_ID)).andExpect(status().isNotFound());

        verifyNoInteractions(interestOfferService, interestAgentService, interestUpdateService);
    }

    private void assertRefusal(InterestException interestException, int status, String code) throws Exception {
        when(interestOfferService.accept(eq(USER_ID), eq(OFFER_ID), any())).thenThrow(interestException);

        mockMvc.perform(post("/users/{userId}/interest-offers/{offerId}/accept", USER_ID, OFFER_ID))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private static InterestAgentSummary agentSummary() {
        ZonedDateTime now = ZonedDateTime.now();

        return new InterestAgentSummary(AGENT_ID, AgentType.NEWS, "Formula 1", "Formula 1 race results", AgentCadence.WEEKLY,
                AgentStatus.ACTIVE, UUID.randomUUID(), now, null, null, null, 0, now);
    }
}
