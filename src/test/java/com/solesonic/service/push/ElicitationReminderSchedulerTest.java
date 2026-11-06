package com.solesonic.service.push;

import com.solesonic.config.push.PushSettings;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.push.PushEvent;
import com.solesonic.repository.chat.ChatRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElicitationReminderSchedulerTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID ELICITATION_ID = UUID.randomUUID();
    private static final PushEvent WAITING = new PushEvent.ElicitationWaiting(CHAT_ID, ELICITATION_ID, false);
    private static final PushEvent REMINDER = new PushEvent.ElicitationWaiting(CHAT_ID, ELICITATION_ID, true);

    @Mock
    private PushNotificationService pushNotificationService;

    @Mock
    private ChatRepository chatRepository;

    private VirtualTimeScheduler virtualTimeScheduler;

    @BeforeEach
    void setUp() {
        virtualTimeScheduler = VirtualTimeScheduler.getOrSet();

        Chat chat = new Chat();
        chat.setId(CHAT_ID);
        chat.setUserId(USER_ID);
        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.of(chat));
    }

    @AfterEach
    void tearDown() {
        VirtualTimeScheduler.reset();
    }

    private ElicitationReminderScheduler scheduler(boolean enabled) {
        PushSettings pushSettings = new PushSettings(enabled, Set.of("fcm.googleapis.com"), 3, 5, Duration.ofSeconds(120),
                Duration.ofSeconds(600), Duration.ofSeconds(45), Duration.ofSeconds(15));

        return new ElicitationReminderScheduler(pushNotificationService, chatRepository, pushSettings);
    }

    @Test
    void pushesAtOnceAndRemindsOnceAfterTheDelay() {
        scheduler(true).waiting(CHAT_ID, ELICITATION_ID);
        virtualTimeScheduler.advanceTime();

        verify(pushNotificationService).push(USER_ID, WAITING);
        verify(pushNotificationService, never()).push(USER_ID, REMINDER);

        virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(119));
        verify(pushNotificationService, never()).push(USER_ID, REMINDER);

        virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(pushNotificationService).push(USER_ID, REMINDER);
    }

    @Test
    void anAnsweredElicitationIsNotReminded() {
        ElicitationReminderScheduler elicitationReminderScheduler = scheduler(true);

        elicitationReminderScheduler.waiting(CHAT_ID, ELICITATION_ID);
        virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(60));
        elicitationReminderScheduler.cancel(ELICITATION_ID);
        virtualTimeScheduler.advanceTimeBy(Duration.ofMinutes(5));

        verify(pushNotificationService, never()).push(USER_ID, REMINDER);
    }

    @Test
    void cancellingAnUnknownElicitationIsHarmless() {
        scheduler(true).cancel(UUID.randomUUID());

        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void aChatWithNoOwnerIsNeverPushed() {
        when(chatRepository.findById(CHAT_ID)).thenReturn(Optional.empty());

        scheduler(true).waiting(CHAT_ID, ELICITATION_ID);
        virtualTimeScheduler.advanceTimeBy(Duration.ofMinutes(5));

        verify(pushNotificationService, never()).push(any(), any());
    }

    @Test
    void schedulesNothingWhileDisabled() {
        scheduler(false).waiting(CHAT_ID, ELICITATION_ID);
        virtualTimeScheduler.advanceTimeBy(Duration.ofMinutes(5));

        verifyNoInteractions(pushNotificationService, chatRepository);
    }
}
