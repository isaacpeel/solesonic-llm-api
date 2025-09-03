package com.solesonic.exception.handler;

import com.solesonic.exception.ChatException;
import com.solesonic.model.SolesonicChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GeneralExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GeneralExceptionHandler.class);

    private final ExceptionService exceptionService;

    private static final String CHAT_EXCEPTION_TEMPLATE = """
            The AI model failed:
            This is often due to the chosen model calling functions incorrectly.
            
            tip: Sometimes it helps if you have a brief conversation first then prompt
                 for an integration to trigger i.e. Creating a Jira
            
            Error Message:
            {message}
            
            """;

    private static final String GENERIC_EXCEPTION_TEMPLATE = """
            I've encountered an unknown exception.  Yell at Isaac about this.
            
            Error Message:
            {message}
            """;

    public static final String EXCEPTION_MESSAGE = "{message}";

    public GeneralExceptionHandler(ExceptionService exceptionService) {
        this.exceptionService = exceptionService;
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<SolesonicChatResponse> handleChatException(RuntimeException exception) {
        String responseMessage = CHAT_EXCEPTION_TEMPLATE.replace(EXCEPTION_MESSAGE, exception.getMessage());

        return exceptionService.buildResponse(responseMessage);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<SolesonicChatResponse> handleRuntimeException(RuntimeException runtimeException) {
        log.error(runtimeException.getMessage(), runtimeException);
        String responseMessage = GENERIC_EXCEPTION_TEMPLATE.replace(EXCEPTION_MESSAGE, runtimeException.getMessage());
        return exceptionService.buildResponse(responseMessage);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SolesonicChatResponse> handleException(Exception exception) {
        log.error(exception.getMessage(), exception);
        String responseMessage = GENERIC_EXCEPTION_TEMPLATE.replace(EXCEPTION_MESSAGE, exception.getMessage());

        return exceptionService.buildResponse(responseMessage);
    }


}
