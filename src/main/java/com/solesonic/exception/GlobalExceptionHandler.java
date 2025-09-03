package com.solesonic.exception;

import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.chat.history.ChatMessage;
import com.solesonic.model.atlassian.jira.issue.JiraIssue;
import com.solesonic.scope.UserRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${JIRA_URL_TEMPLATE}")
    private String jiraUrlTemplate;

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
        String jiraUri = jiraUrlTemplate.replace(ISSUE_ID, jiraIssue.key());

        String responseMessage = DUPLICATE_JIRA_MESSAGE_TEMPLATE.replace(ISSUE_LINK, jiraUri);

        return buildResponse(responseMessage);
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<?> handleWebClientResponseException(WebClientResponseException webClientResponseException) {
        AtlassianTokenException atlassianTokenException;
        
        if (webClientResponseException.getStatusCode() == HttpStatus.BAD_REQUEST) {
            log.warn("Atlassian token refresh failed with 400 - likely invalid refresh token");
            atlassianTokenException = new AtlassianTokenException("Invalid refresh token", "RECONNECT_REQUIRED", false, webClientResponseException);
        } else if (webClientResponseException.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS ||
                webClientResponseException.getStatusCode().is5xxServerError()) {
            log.warn("Atlassian token refresh failed with retriable error: {}", webClientResponseException.getStatusCode());
            atlassianTokenException = new AtlassianTokenException("Upstream error", "UPSTREAM_ERROR", true, webClientResponseException);
        } else {
            log.error("Atlassian token refresh failed with non-retriable error: {}", webClientResponseException.getStatusCode());
            atlassianTokenException = new AtlassianTokenException("Atlassian API error", "API_ERROR", false, webClientResponseException);
        }
        
        return handleAtlassianTokenException(atlassianTokenException);
    }

    @ExceptionHandler(AtlassianTokenException.class)
    public ResponseEntity<?> handleAtlassianTokenException(AtlassianTokenException atlassianTokenException) {
        String errorCode = atlassianTokenException.getErrorCode();
        boolean retriable = atlassianTokenException.isRetriable();

        return switch (errorCode) {
            case "RECONNECT_REQUIRED" -> {
                log.warn("RECONNECT_REQUIRED - {}", atlassianTokenException.getMessage());
                yield ResponseEntity.status(HttpStatus.GONE)
                        .body(java.util.Map.of("error", "RECONNECT_REQUIRED",
                                "message", "User must re-consent to Atlassian access",
                                "retriable", false));
            }
            case "UPSTREAM_ERROR", "NETWORK_ERROR" -> {
                log.warn("Retriable error - {}", atlassianTokenException.getMessage());
                yield ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(java.util.Map.of("error", "UPSTREAM_ERROR",
                                "message", "Temporary upstream service issue",
                                "retriable", true));
            }
            case "ROTATION_TIMEOUT", "ROTATION_INTERRUPTED" -> {
                log.warn("Rotation issue - {}", atlassianTokenException.getMessage());
                yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(java.util.Map.of("error", "SERVICE_UNAVAILABLE",
                                "message", "Service temporarily unavailable",
                                "retriable", true));
            }
            default -> {
                log.error("API error - {}", atlassianTokenException.getMessage());
                yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(java.util.Map.of("error", "INTERNAL_ERROR",
                                "message", "Internal service error",
                                "retriable", retriable));
            }
        };
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntimeException(RuntimeException runtimeException) {
        // Check if this is from Atlassian token operations by examining stack trace
        StackTraceElement[] stackTrace = runtimeException.getStackTrace();
        boolean isAtlassianTokenOperation = false;
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("AtlassianTokenBrokerService") && 
                element.getMethodName().equals("callAtlassianTokenEndpoint")) {
                isAtlassianTokenOperation = true;
                break;
            }
        }
        
        if (isAtlassianTokenOperation) {
            log.error("Unexpected error calling Atlassian token endpoint", runtimeException);
            AtlassianTokenException atlassianTokenException = new AtlassianTokenException("Network error", "NETWORK_ERROR", true, runtimeException);
            return handleAtlassianTokenException(atlassianTokenException);
        }
        
        // Fall back to generic handling
        log.error(runtimeException.getMessage(), runtimeException);
        String responseMessage = GENERIC_EXCEPTION_TEMPLATE.replace(EXCEPTION_MESSAGE, runtimeException.getMessage());
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
