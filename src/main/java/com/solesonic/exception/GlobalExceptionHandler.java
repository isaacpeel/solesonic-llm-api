package com.solesonic.exception;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.atlassian.jira.issue.JiraIssue;
import com.solesonic.scope.UserRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.function.client.ClientResponse;

import javax.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;

import static com.solesonic.tools.jira.CreateJiraTools.JIRA_URL_TEMPLATE;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String DUPLICATE_JIRA_MESSAGE_TEMPLATE = """
            I've gone into an error state but still managed managed to create your Jira issue.
            Here is a link: {issueLink}
            """;

    private static final String CHAT_EXCEPTION_TEMPLATE = """
            The AI model failed:
            This is often due to the chosen model calling functions incorrectly.
            
            tip: Sometimes it helps if you have a brief conversation first then promot
                 for an integration to trigger i.e. Creating a Jira
            
            Error Message:
            {message}
            
            """;

    private static final String GENERIC_EXCEPTION_TEMPLATE = """
            I've encountered an unknown exception.  Yell at Isaac about this.
            
            Error Message:
            {message}
            """;

    public static final String ISSUE_ID = "{issueId}";
    public static final String ISSUE_LINK = "{issueLink}";
    public static final String EXCEPTION_MESSAGE = "{message}";

    private final UserRequestContext userRequestContext;

    public GlobalExceptionHandler(UserRequestContext userRequestContext) {
        this.userRequestContext = userRequestContext;
    }

    @ExceptionHandler(JiraException.class)
    public ResponseEntity<JiraExceptionResponse> handleJiraException(JiraException jiraException) {
        ClientResponse clientResponse = jiraException.getResponse();
        URI requestUri = clientResponse.request().getURI();

        JiraExceptionResponse jiraExceptionResponse = new JiraExceptionResponse(requestUri.toASCIIString(), jiraException.getMessage());
        return new ResponseEntity<>(jiraExceptionResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler({
            ChatException.class
    })
    public ResponseEntity<SolesonicChatResponse> handleChatException(RuntimeException exception) {
        String responseMessage = CHAT_EXCEPTION_TEMPLATE.replace(EXCEPTION_MESSAGE, exception.getMessage());

        return buildResponse(responseMessage);
    }

    @ExceptionHandler(DuplicateJiraCreationException.class)
    public ResponseEntity<SolesonicChatResponse> handleDuplicateJiraException(DuplicateJiraCreationException duplicateJiraCreationException) {
        JiraIssue jiraIssue = duplicateJiraCreationException.getJiraIssue();
        String jiraUri = JIRA_URL_TEMPLATE.replace(ISSUE_ID, jiraIssue.key());

        String responseMessage = DUPLICATE_JIRA_MESSAGE_TEMPLATE.replace(ISSUE_LINK, jiraUri);

        return buildResponse(responseMessage);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SolesonicChatResponse> handleException(Exception exception) {
        log.error(exception.getMessage(), exception);
        String responseMessage = GENERIC_EXCEPTION_TEMPLATE.replace(EXCEPTION_MESSAGE, exception.getMessage());

        return buildResponse(responseMessage);
    }

    @NotNull
    private ResponseEntity<SolesonicChatResponse> buildResponse(String responseMessage) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessage(responseMessage);
        chatMessage.setMessageType(MessageType.SYSTEM);

        String chatModel = userRequestContext.getChatModel();
        chatMessage.setModel(chatModel);

        SolesonicChatResponse solesonicChatResponse = new SolesonicChatResponse(UUID.randomUUID(), chatMessage);

        return ResponseEntity.ok(solesonicChatResponse);
    }
}
