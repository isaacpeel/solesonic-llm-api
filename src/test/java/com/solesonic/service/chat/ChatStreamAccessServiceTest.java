package com.solesonic.service.chat;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.ollama.ChatRepository;
import com.solesonic.service.chat.ChatStreamAccessService.ChatAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatStreamAccessServiceTest {
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private ChatRepository chatRepository;

    private ChatStreamAccessService chatStreamAccessService;

    @BeforeEach
    void setUp() {
        chatStreamAccessService = new ChatStreamAccessService(chatRepository);
    }

    @SuppressWarnings("DataFlowIssue")
    private static Authentication tokenFor(UUID subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", subject.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        return new TestingAuthenticationToken(jwt, null);
    }

    private void chatOwnedBy(UUID ownerId) {
        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(ownerId);

        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(chat));
    }

    @Test
    void grantsAccessToOwnChat() {
        chatOwnedBy(USER_ID);

        assertThat(chatStreamAccessService.forExistingChat(tokenFor(USER_ID), CHAT_ID, USER_ID))
                .isEqualTo(ChatAccess.GRANTED);
    }

    /**
     * Both ids came from the URL, so before this check a valid token plus someone else's two ids
     * read someone else's conversation.
     */
    @Test
    void deniesAccessWhenThePathUserIsNotTheTokenSubject() {
        assertThat(chatStreamAccessService.forExistingChat(tokenFor(OTHER_USER_ID), CHAT_ID, USER_ID))
                .isEqualTo(ChatAccess.FORBIDDEN);
    }

    @Test
    void deniesAccessToAChatOwnedBySomeoneElse() {
        chatOwnedBy(OTHER_USER_ID);

        assertThat(chatStreamAccessService.forExistingChat(tokenFor(USER_ID), CHAT_ID, USER_ID))
                .isEqualTo(ChatAccess.FORBIDDEN);
    }

    @Test
    void reportsAnUnknownChatAsNotFound() {
        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.empty());

        assertThat(chatStreamAccessService.forExistingChat(tokenFor(USER_ID), CHAT_ID, USER_ID))
                .isEqualTo(ChatAccess.NOT_FOUND);
    }

    @Test
    void grantsAccessToANewChatForTheTokenSubject() {
        assertThat(chatStreamAccessService.forNewChat(tokenFor(USER_ID), USER_ID))
                .isEqualTo(ChatAccess.GRANTED);
    }

    @Test
    void deniesANewChatOnBehalfOfAnotherUser() {
        assertThat(chatStreamAccessService.forNewChat(tokenFor(OTHER_USER_ID), USER_ID))
                .isEqualTo(ChatAccess.FORBIDDEN);
    }

    /**
     * The local and test profiles run without a JWT, and chat ownership is still checked from the
     * database — so an unauthenticated local run must not be locked out of its own chats.
     */
    @Test
    void fallsBackToChatOwnershipWithoutAJwt() {
        chatOwnedBy(USER_ID);

        assertThat(chatStreamAccessService.forExistingChat(null, CHAT_ID, USER_ID))
                .isEqualTo(ChatAccess.GRANTED);
    }
}
