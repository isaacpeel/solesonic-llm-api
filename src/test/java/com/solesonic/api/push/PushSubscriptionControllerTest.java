package com.solesonic.api.push;

import com.solesonic.exception.handler.PushExceptionHandler;
import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushErrorCode;
import com.solesonic.model.push.PushSubscriptionRequest;
import com.solesonic.model.push.PushSubscriptionSummary;
import com.solesonic.service.push.PushSubscriptionService;
import com.solesonic.service.security.ResourceOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushSubscriptionControllerTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14)";
    private static final String BODY = """
            {"subscription":{"endpoint":"https://fcm.googleapis.com/fcm/send/token","expirationTime":null,
             "keys":{"p256dh":"BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM","auth":"tBHItJI5svbpez7KI4CCXg"}},
             "label":"Chrome on Android"}
            """;

    @Mock
    private PushSubscriptionService pushSubscriptionService;

    @Mock
    private ResourceOwnershipService resourceOwnershipService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PushSubscriptionController(pushSubscriptionService, resourceOwnershipService))
                .setControllerAdvice(new PushExceptionHandler())
                .build();

        when(resourceOwnershipService.isOwner(eq(USER_ID), any())).thenReturn(true);
        when(resourceOwnershipService.isOwner(eq(OTHER_USER_ID), any())).thenReturn(false);
    }

    private static PushSubscriptionSummary summary() {
        ZonedDateTime created = ZonedDateTime.parse("2026-10-20T12:00:00Z");

        return new PushSubscriptionSummary(SUBSCRIPTION_ID, "Chrome on Android", USER_AGENT, created, created, null);
    }

    @Test
    void listsTheCallersSubscriptionsWithoutTheirEndpointsOrKeys() throws Exception {
        when(pushSubscriptionService.list(USER_ID)).thenReturn(List.of(summary()));

        String body = mockMvc.perform(get("/users/{userId}/push-subscriptions", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(SUBSCRIPTION_ID.toString()))
                .andExpect(jsonPath("$[0].label").value("Chrome on Android"))
                .andExpect(jsonPath("$[0].userAgent").value(USER_AGENT))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("endpoint", "p256dh", "auth", "keys");
        assertThat(body).contains("\"lastDelivered\":null");
    }

    @Test
    void aNewSubscriptionIsCreated() throws Exception {
        when(pushSubscriptionService.upsert(eq(USER_ID), any(), eq(USER_AGENT)))
                .thenReturn(new PushSubscriptionService.UpsertResult(summary(), true));

        mockMvc.perform(put("/users/{userId}/push-subscriptions", USER_ID)
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SUBSCRIPTION_ID.toString()));

        ArgumentCaptor<PushSubscriptionRequest> captured = ArgumentCaptor.forClass(PushSubscriptionRequest.class);
        verify(pushSubscriptionService).upsert(eq(USER_ID), captured.capture(), eq(USER_AGENT));
        assertThat(captured.getValue().label()).isEqualTo("Chrome on Android");
        assertThat(captured.getValue().subscription().endpoint()).isEqualTo("https://fcm.googleapis.com/fcm/send/token");
        assertThat(captured.getValue().subscription().keys().auth()).isEqualTo("tBHItJI5svbpez7KI4CCXg");
    }

    @Test
    void anExistingEndpointIsUpdated() throws Exception {
        when(pushSubscriptionService.upsert(eq(USER_ID), any(), any()))
                .thenReturn(new PushSubscriptionService.UpsertResult(summary(), false));

        mockMvc.perform(put("/users/{userId}/push-subscriptions", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void anUnsupportedBrowserIsABadRequest() throws Exception {
        when(pushSubscriptionService.upsert(eq(USER_ID), any(), any()))
                .thenThrow(new PushException(PushErrorCode.UNSUPPORTED_BROWSER, "Push notifications are supported in Chrome only."));

        mockMvc.perform(put("/users/{userId}/push-subscriptions", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_BROWSER"))
                .andExpect(jsonPath("$.message").value("Push notifications are supported in Chrome only."));
    }

    @Test
    void anInvalidSubscriptionIsABadRequest() throws Exception {
        when(pushSubscriptionService.upsert(eq(USER_ID), any(), any()))
                .thenThrow(new PushException(PushErrorCode.INVALID_SUBSCRIPTION, "The subscription is not valid."));

        mockMvc.perform(put("/users/{userId}/push-subscriptions", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION"));
    }

    @Test
    void subscribingWhileDisabledIsServiceUnavailable() throws Exception {
        when(pushSubscriptionService.upsert(eq(USER_ID), any(), any())).thenThrow(PushException.disabled());

        mockMvc.perform(put("/users/{userId}/push-subscriptions", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PUSH_DISABLED"));
    }

    @Test
    void deletesASubscription() throws Exception {
        mockMvc.perform(delete("/users/{userId}/push-subscriptions/{subscriptionId}", USER_ID, SUBSCRIPTION_ID))
                .andExpect(status().isNoContent());

        verify(pushSubscriptionService).delete(USER_ID, SUBSCRIPTION_ID);
    }

    @Test
    void deletingAnUnknownSubscriptionIsNotFound() throws Exception {
        doThrow(PushException.notFound()).when(pushSubscriptionService).delete(USER_ID, SUBSCRIPTION_ID);

        mockMvc.perform(delete("/users/{userId}/push-subscriptions/{subscriptionId}", USER_ID, SUBSCRIPTION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anAcceptedTestPushIsNoContent() throws Exception {
        mockMvc.perform(post("/users/{userId}/push-subscriptions/{subscriptionId}/test", USER_ID, SUBSCRIPTION_ID))
                .andExpect(status().isNoContent());

        verify(pushSubscriptionService).test(USER_ID, SUBSCRIPTION_ID);
    }

    @Test
    void aTestPushToADeadSubscriptionIsGone() throws Exception {
        doThrow(new PushException(PushErrorCode.SUBSCRIPTION_GONE, "The browser is no longer subscribed."))
                .when(pushSubscriptionService).test(USER_ID, SUBSCRIPTION_ID);

        mockMvc.perform(post("/users/{userId}/push-subscriptions/{subscriptionId}/test", USER_ID, SUBSCRIPTION_ID))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_GONE"));
    }

    @Test
    void aTestPushThatFailedOtherwiseIsABadGateway() throws Exception {
        doThrow(new PushException(PushErrorCode.DELIVERY_FAILED, "The push service did not accept the notification."))
                .when(pushSubscriptionService).test(USER_ID, SUBSCRIPTION_ID);

        mockMvc.perform(post("/users/{userId}/push-subscriptions/{subscriptionId}/test", USER_ID, SUBSCRIPTION_ID))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DELIVERY_FAILED"));
    }

    /**
     * Another user's path is a {@code 404} on every method, and the service is never asked: whether
     * that user has subscriptions at all is not the caller's to learn.
     */
    @Test
    void everyMethodAnswersNotFoundForAnotherUser() throws Exception {
        mockMvc.perform(get("/users/{userId}/push-subscriptions", OTHER_USER_ID))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/users/{userId}/push-subscriptions", OTHER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/users/{userId}/push-subscriptions/{subscriptionId}", OTHER_USER_ID, SUBSCRIPTION_ID))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/users/{userId}/push-subscriptions/{subscriptionId}/test", OTHER_USER_ID, SUBSCRIPTION_ID))
                .andExpect(status().isNotFound());

        verifyNoInteractions(pushSubscriptionService);
    }
}
