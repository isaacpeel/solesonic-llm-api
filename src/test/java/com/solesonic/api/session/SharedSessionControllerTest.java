package com.solesonic.api.session;

import com.solesonic.exception.handler.SessionExceptionHandler;
import com.solesonic.exception.session.SessionException;
import com.solesonic.model.session.SessionErrorCode;
import com.solesonic.model.session.SessionInviteStatus;
import com.solesonic.model.session.SessionInviteSummary;
import com.solesonic.model.session.SessionInvitesRequest;
import com.solesonic.model.session.SessionMessageAccepted;
import com.solesonic.model.session.SessionMessageRequest;
import com.solesonic.model.session.SessionMode;
import com.solesonic.model.session.SessionStatus;
import com.solesonic.model.session.SharedConversationRequest;
import com.solesonic.model.session.SharedSessionSummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.model.prompt.ModelSlashCommand;
import com.solesonic.model.prompt.ToolSlashCommand;
import com.solesonic.model.session.SharedSession;
import com.solesonic.service.redis.RedisStreamingChatService;
import com.solesonic.service.session.SharedCommandPolicy;
import com.solesonic.service.session.SessionMessageService;
import com.solesonic.service.session.SessionStreamService;
import com.solesonic.service.session.SharedConversationService;
import com.solesonic.service.session.SharedSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SharedSessionControllerTest {
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-26T12:00:00Z");

    private final UUID callerId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID chatId = UUID.randomUUID();

    @Mock
    private SharedSessionService sharedSessionService;
    @Mock
    private SharedConversationService sharedConversationService;
    @Mock
    private SessionMessageService sessionMessageService;
    @Mock
    private SessionStreamService sessionStreamService;
    @Mock
    private RedisStreamingChatService redisStreamingChatService;
    @Mock
    private SharedCommandPolicy sharedCommandPolicy;
    @Mock
    private UserRequestContext userRequestContext;
    @Mock
    private Authentication authentication;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SharedSessionController controller = new SharedSessionController(sharedSessionService, sharedConversationService,
                sessionMessageService, sessionStreamService, redisStreamingChatService, sharedCommandPolicy, userRequestContext);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SessionExceptionHandler())
                .build();

        when(userRequestContext.getUserId()).thenReturn(callerId);
    }

    private SharedSessionSummary summary() {
        return new SharedSessionSummary(sessionId, chatId, SessionMode.CONVERSATION, "Trip plans", callerId, null, SessionStatus.ACTIVE,
                null, List.of(), List.of(new SharedSessionSummary.PendingInvitee(UUID.randomUUID(), "sam", "Sam")),
                null, null, null, false, NOW, NOW);
    }

    @Test
    void sharingAChatAnswersCreatedWithTheSummary() throws Exception {
        UUID sourceChatId = UUID.randomUUID();
        when(sharedConversationService.create(eq(callerId), any(SharedConversationRequest.class))).thenReturn(summary());

        mockMvc.perform(post("/shared-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceChatId\":\"" + sourceChatId + "\",\"usernames\":[\"sam\"],\"name\":\"Trip\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.mode").value("CONVERSATION"))
                .andExpect(jsonPath("$.pendingInvitees[0].username").value("sam"));

        ArgumentCaptor<SharedConversationRequest> request = ArgumentCaptor.forClass(SharedConversationRequest.class);
        verify(sharedConversationService).create(eq(callerId), request.capture());
        assertThat(request.getValue()).isEqualTo(new SharedConversationRequest(sourceChatId, List.of("sam"), "Trip"));
    }

    @Test
    void unknownUsernamesAreA404ThatNamesThem() throws Exception {
        when(sharedConversationService.create(eq(callerId), any()))
                .thenThrow(new SessionException(SessionErrorCode.PARTICIPANT_NOT_FOUND, "No such users", List.of("ghost")));

        mockMvc.perform(post("/shared-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"ghost\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_FOUND"))
                .andExpect(jsonPath("$.usernames[0]").value("ghost"));
    }

    @Test
    void aChatThatIsMidRunIsA409() throws Exception {
        when(sharedConversationService.create(eq(callerId), any()))
                .thenThrow(new SessionException(SessionErrorCode.CHAT_STREAMING, "Wait"));

        mockMvc.perform(post("/shared-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"sam\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invitingAnswersCreatedWithTheInvites() throws Exception {
        UUID inviteId = UUID.randomUUID();
        when(sharedConversationService.invite(eq(sessionId), eq(callerId), any(SessionInvitesRequest.class)))
                .thenReturn(List.of(new SessionInviteSummary(inviteId, callerId, "alex", "Alex", UUID.randomUUID(), "kim", "Kim",
                        SessionMode.CONVERSATION, "Trip plans", null, null, SessionInviteStatus.PENDING, sessionId, NOW, NOW.plusDays(1))));

        mockMvc.perform(post("/shared-sessions/{id}/invites", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"kim\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inviteId.toString()))
                .andExpect(jsonPath("$[0].sessionName").value("Trip plans"));

        ArgumentCaptor<SessionInvitesRequest> request = ArgumentCaptor.forClass(SessionInvitesRequest.class);
        verify(sharedConversationService).invite(eq(sessionId), eq(callerId), request.capture());
        assertThat(request.getValue().usernames()).containsExactly("kim");
    }

    @Test
    void aNonParticipantInvitingIsA404() throws Exception {
        when(sharedConversationService.invite(eq(sessionId), eq(callerId), any()))
                .thenThrow(new SessionException(SessionErrorCode.SESSION_NOT_FOUND, "Shared session not found"));

        mockMvc.perform(post("/shared-sessions/{id}/invites", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"kim\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void aParticipantWhoIsNotTheOwnerInvitingIsA404() throws Exception {
        when(sharedConversationService.invite(eq(sessionId), eq(callerId), any()))
                .thenThrow(new SessionException(SessionErrorCode.NOT_SESSION_OWNER, "Session not found"));

        mockMvc.perform(post("/shared-sessions/{id}/invites", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"kim\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_SESSION_OWNER"));
    }

    @Test
    void aFullChatIsA409() throws Exception {
        when(sharedConversationService.invite(eq(sessionId), eq(callerId), any()))
                .thenThrow(new SessionException(SessionErrorCode.TOO_MANY_PARTICIPANTS, "Full"));

        mockMvc.perform(post("/shared-sessions/{id}/invites", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernames\":[\"kim\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void sendingPassesTheAuthenticationOn() throws Exception {
        UUID runId = UUID.randomUUID();
        when(sessionMessageService.send(eq(sessionId), eq(callerId), any(SessionMessageRequest.class), eq(authentication)))
                .thenReturn(new SessionMessageAccepted(runId, UUID.randomUUID()));

        mockMvc.perform(post("/shared-sessions/{id}/messages", sessionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()));
    }

    @Test
    void aSlashCommandInAConversationIsA400() throws Exception {
        when(sessionMessageService.send(eq(sessionId), eq(callerId), any(), any()))
                .thenThrow(new SessionException(SessionErrorCode.COMMAND_NOT_ALLOWED, "No commands"));

        mockMvc.perform(post("/shared-sessions/{id}/messages", sessionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"/model x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMessageBodyCarriesTextAttachmentsAndCommands() throws Exception {
        UUID attachmentId = UUID.randomUUID();
        when(sessionMessageService.send(eq(sessionId), eq(callerId), any(SessionMessageRequest.class), eq(authentication)))
                .thenReturn(new SessionMessageAccepted(UUID.randomUUID(), UUID.randomUUID()));

        mockMvc.perform(post("/shared-sessions/{id}/messages", sessionId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\",\"attachmentIds\":[\"" + attachmentId + "\"],\"commands\":[\"web_search\"]}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<SessionMessageRequest> request = ArgumentCaptor.forClass(SessionMessageRequest.class);
        verify(sessionMessageService).send(eq(sessionId), eq(callerId), request.capture(), eq(authentication));
        assertThat(request.getValue().text()).isEmpty();
        assertThat(request.getValue().attachmentIds()).containsExactly(attachmentId);
        assertThat(request.getValue().commands()).containsExactly("web_search");
    }

    @Test
    void aTextOnlyBodyStillWorksForAgentModeAndAnswers() throws Exception {
        when(sessionMessageService.send(eq(sessionId), eq(callerId), any(SessionMessageRequest.class), any()))
                .thenReturn(new SessionMessageAccepted(UUID.randomUUID(), UUID.randomUUID()));

        mockMvc.perform(post("/shared-sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptAnswer\":{\"elicitationId\":\"" + UUID.randomUUID() + "\",\"action\":\"accept\",\"content\":{\"choice\":\"play\"}}}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<SessionMessageRequest> request = ArgumentCaptor.forClass(SessionMessageRequest.class);
        verify(sessionMessageService).send(eq(sessionId), eq(callerId), request.capture(), any());
        assertThat(request.getValue().promptAnswer().action()).isEqualTo("accept");
        assertThat(request.getValue().attachmentIds()).isNull();
    }

    @Test
    void aRefusedCommandIs400AndNamesIt() throws Exception {
        when(sessionMessageService.send(eq(sessionId), eq(callerId), any(), any()))
                .thenThrow(new SessionException(SessionErrorCode.COMMAND_NOT_ALLOWED, "The command /feud is not available in a shared chat."));

        mockMvc.perform(post("/shared-sessions/{id}/messages", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hi\",\"commands\":[\"feud\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMAND_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/feud")));
    }

    private SharedSession conversation(SessionMode mode) {
        SharedSession sharedSession = new SharedSession();
        sharedSession.setId(sessionId);
        sharedSession.setChatId(chatId);
        sharedSession.setMode(mode);
        sharedSession.setStatus(SessionStatus.ACTIVE);

        return sharedSession;
    }

    @Test
    void theCommandsOfAConversationAreTheAllowedCatalogueInTheUsualShape() throws Exception {
        when(sharedSessionService.requireSeated(sessionId, callerId)).thenReturn(conversation(SessionMode.CONVERSATION));
        when(sharedCommandPolicy.catalogue(null)).thenReturn(List.of(new ModelSlashCommand(),
                new ToolSlashCommand("web_search", "web_search", "Search the web", null)));

        mockMvc.perform(get("/shared-sessions/{id}/commands", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands.length()").value(2))
                .andExpect(jsonPath("$.commands[0].command").value("model"))
                .andExpect(jsonPath("$.commands[0].commandType").value("model"))
                .andExpect(jsonPath("$.commands[1].command").value("web_search"))
                .andExpect(jsonPath("$.commands[1].commandType").value("tool"));
    }

    @Test
    void theCommandsSearchIsPassedOn() throws Exception {
        when(sharedSessionService.requireSeated(sessionId, callerId)).thenReturn(conversation(SessionMode.CONVERSATION));
        when(sharedCommandPolicy.catalogue("web")).thenReturn(List.of());

        mockMvc.perform(get("/shared-sessions/{id}/commands", sessionId).param("command", "web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands.length()").value(0));
    }

    @Test
    void someoneNotSeatedGetsA404ForTheCommands() throws Exception {
        when(sharedSessionService.requireSeated(sessionId, callerId)).thenThrow(new SessionException(SessionErrorCode.SESSION_NOT_FOUND, "No such session"));

        mockMvc.perform(get("/shared-sessions/{id}/commands", sessionId)).andExpect(status().isNotFound());

        verify(sharedCommandPolicy, never()).catalogue(any());
    }

    @Test
    void anAgentSessionOffersNoCommands() throws Exception {
        when(sharedSessionService.requireSeated(sessionId, callerId)).thenReturn(conversation(SessionMode.AGENT));

        mockMvc.perform(get("/shared-sessions/{id}/commands", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commands.length()").value(0));

        verify(sharedCommandPolicy, never()).catalogue(any());
    }
}
