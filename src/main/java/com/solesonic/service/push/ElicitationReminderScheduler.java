package com.solesonic.service.push;

import com.solesonic.config.push.PushSettings;
import com.solesonic.model.chat.history.Chat;
import com.solesonic.model.push.PushEvent;
import com.solesonic.repository.chat.ChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes that an elicitation is waiting, and reminds once if it is still unanswered after the
 * configured delay.
 * <p>
 * The reminder lives on the instance holding the parked tool call: {@link #waiting} registers it
 * before returning, and the same call's completion — answer, timeout or {@code closeChat} — is what
 * cancels it. If the instance dies, the parked call and its reminder die together.
 * <p>
 * The owner is read from the chat row, the only place it is recorded.
 */
@Component
public class ElicitationReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ElicitationReminderScheduler.class);

    private final PushNotificationService pushNotificationService;
    private final ChatRepository chatRepository;
    private final PushSettings pushSettings;
    private final Map<UUID, Disposable> reminders = new ConcurrentHashMap<>();

    public ElicitationReminderScheduler(PushNotificationService pushNotificationService,
                                        ChatRepository chatRepository,
                                        PushSettings pushSettings) {
        this.pushNotificationService = pushNotificationService;
        this.chatRepository = chatRepository;
        this.pushSettings = pushSettings;
    }

    public void waiting(UUID chatId, UUID elicitationId) {
        if (!pushSettings.enabled()) {
            return;
        }

        owner(chatId).subscribe(
                userId -> pushNotificationService.push(userId, new PushEvent.ElicitationWaiting(chatId, elicitationId, false)),
                error -> log.warn("Could not push elicitation {} for chat {}: {}", elicitationId, chatId, error.getClass().getSimpleName()));

        Disposable reminder = Mono.delay(pushSettings.elicitationReminderDelay())
                .then(owner(chatId))
                .doFinally(_ -> reminders.remove(elicitationId))
                .subscribe(
                        userId -> pushNotificationService.push(userId, new PushEvent.ElicitationWaiting(chatId, elicitationId, true)),
                        error -> log.warn("Could not remind elicitation {} for chat {}: {}", elicitationId, chatId, error.getClass().getSimpleName()));

        reminders.put(elicitationId, reminder);
    }

    public void cancel(UUID elicitationId) {
        Disposable reminder = reminders.remove(elicitationId);

        if (reminder != null) {
            reminder.dispose();
        }
    }

    private Mono<UUID> owner(UUID chatId) {
        return Mono.fromCallable(() -> chatRepository.findById(chatId).map(Chat::getUserId).orElse(null))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
