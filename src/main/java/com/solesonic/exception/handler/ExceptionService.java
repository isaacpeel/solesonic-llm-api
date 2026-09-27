package com.solesonic.exception.handler;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.history.ChatMessage;
import jakarta.annotation.Nonnull;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ExceptionService {

    @Nonnull
    public ResponseEntity<SolesonicChatResponse> buildResponse(String responseMessage) {
        //No model is named: this message reports a failure rather than an answer, so no model ran
        //to report one, and responseMetadata is the only thing that ever carries a model now.
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessage(responseMessage);
        chatMessage.setMessageType(MessageType.SYSTEM);

        SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(UUID.randomUUID(), chatMessage);

        return ResponseEntity.ok(solesonicChatResponse);
    }
}
