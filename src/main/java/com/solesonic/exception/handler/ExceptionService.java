package com.solesonic.exception.handler;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.scope.UserRequestContext;
import jakarta.annotation.Nonnull;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ExceptionService {

    private final UserRequestContext userRequestContext;

    public ExceptionService(UserRequestContext userRequestContext) {
        this.userRequestContext = userRequestContext;
    }

    @Nonnull
    public ResponseEntity<SolesonicChatResponse> buildResponse(String responseMessage) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessage(responseMessage);
        chatMessage.setMessageType(MessageType.SYSTEM);

        String chatModel = userRequestContext.getChatModel();
        chatMessage.setModel(chatModel);

        SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(UUID.randomUUID(), chatMessage);

        return ResponseEntity.ok(solesonicChatResponse);
    }
}
