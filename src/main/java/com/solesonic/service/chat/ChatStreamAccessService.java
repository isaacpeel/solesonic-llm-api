package com.solesonic.service.chat;

import com.solesonic.model.chat.history.Chat;
import com.solesonic.repository.ollama.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

import static com.solesonic.security.JwtUserRequestFilter.SUB;

/**
 * Decides whether a caller may open — or resume — a chat's event stream.
 * <p>
 * The streaming endpoints take both {@code chatId} and {@code userId} from the path, and the Redis
 * stream key is built from that pair. Authentication alone therefore proved nothing: any valid
 * token plus someone else's two ids read someone else's conversation. Both halves are checked here
 * instead — the path user must be the token's subject, and the chat must belong to that user.
 */
@Service
public class ChatStreamAccessService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamAccessService.class);

    public enum ChatAccess {
        GRANTED,
        FORBIDDEN,
        NOT_FOUND
    }

    private final ChatRepository chatRepository;

    public ChatStreamAccessService(ChatRepository chatRepository) {
        this.chatRepository = chatRepository;
    }

    /**
     * For a chat that does not exist yet — there is nothing to own, so only the caller's identity
     * is in question.
     */
    public ChatAccess forNewChat(Authentication authentication, UUID userId) {
        return isCaller(authentication, userId) ? ChatAccess.GRANTED : ChatAccess.FORBIDDEN;
    }

    public ChatAccess forExistingChat(Authentication authentication, UUID chatId, UUID userId) {
        if (!isCaller(authentication, userId)) {
            return ChatAccess.FORBIDDEN;
        }

        Optional<Chat> chat = chatRepository.findById(chatId);

        if (chat.isEmpty()) {
            log.info("Denying stream access to unknown chat {}", chatId);

            return ChatAccess.NOT_FOUND;
        }

        if (!userId.equals(chat.get().getUserId())) {
            log.warn("Denying stream access to chat {} — not owned by user {}", chatId, userId);

            return ChatAccess.FORBIDDEN;
        }

        return ChatAccess.GRANTED;
    }

    /**
     * Enforced only against a JWT subject. The local and test profiles run without one — see
     * {@code LocalJwtUserRequestFilter}, which falls back to the first stored user — and a request
     * that reached a controller at all has already passed the filter chain.
     */
    private boolean isCaller(Authentication authentication, UUID userId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return true;
        }

        String subject = jwt.getClaimAsString(SUB);

        if (subject == null) {
            return true;
        }

        boolean matches = userId.toString().equalsIgnoreCase(subject);

        if (!matches) {
            log.warn("Denying stream access — token subject {} does not match path user {}", subject, userId);
        }

        return matches;
    }
}
