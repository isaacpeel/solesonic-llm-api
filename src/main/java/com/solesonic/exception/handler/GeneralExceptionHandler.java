package com.solesonic.exception.handler;

import com.solesonic.exception.ChatException;
import com.solesonic.model.SolesonicChatResponse;
import com.solesonic.model.security.SecurityEventReason;
import com.solesonic.service.security.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static com.solesonic.model.security.SecurityEvent.METHOD_REJECTED;
import static com.solesonic.model.security.SecurityEvent.UNKNOWN_ROUTE;

@ControllerAdvice
public class GeneralExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GeneralExceptionHandler.class);

    private final ExceptionService exceptionService;
    private final SecurityEventLogger securityEventLogger;

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

    public GeneralExceptionHandler(ExceptionService exceptionService, SecurityEventLogger securityEventLogger) {
        this.exceptionService = exceptionService;
        this.securityEventLogger = securityEventLogger;
    }

    /**
     * A path this application does not serve, reached by a request that authenticated. Answered
     * with a bare 404: a scanner learns nothing from it, and the detail goes to the security log
     * where a jail can act on it.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException noResourceFoundException,
                                                      HttpServletRequest request) {
        log.debug("No resource found: {}", noResourceFoundException.getResourcePath());
        securityEventLogger.log(UNKNOWN_ROUTE, request, HttpStatus.NOT_FOUND.value(), SecurityEventReason.UNKNOWN_ROUTE);

        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException methodNotSupportedException,
                                                         HttpServletRequest request) {
        log.debug("Method not supported: {}", methodNotSupportedException.getMethod());
        securityEventLogger.log(METHOD_REJECTED, request, HttpStatus.METHOD_NOT_ALLOWED.value(), SecurityEventReason.METHOD_NOT_ALLOWED);

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    /**
     * The exception message quotes the body it failed to parse, so it is logged at debug and never
     * returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> handleMessageNotReadable(HttpMessageNotReadableException messageNotReadableException,
                                                         HttpServletRequest request) {
        log.debug("Unreadable request body", messageNotReadableException);
        securityEventLogger.log(METHOD_REJECTED, request, HttpStatus.BAD_REQUEST.value(), SecurityEventReason.MALFORMED_BODY);

        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<SolesonicChatResponse> handleChatException(RuntimeException exception) {
        log.error(exception.getMessage(), exception);
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

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        log.debug("Async request not usable (client disconnected): {}", e.getMessage());
    }


}
