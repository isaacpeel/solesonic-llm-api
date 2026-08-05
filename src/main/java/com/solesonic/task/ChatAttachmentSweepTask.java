package com.solesonic.task;

import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes staged attachments that were uploaded but never sent. This is what bounds attachment
 * storage — an attachment only stops being staged when a chat message claims it.
 */
@Component
@ConditionalOnProperty(name = "solesonic.llm.attachment.sweep.enabled", havingValue = "true")
public class ChatAttachmentSweepTask {
    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentSweepTask.class);

    private final ChatAttachmentService chatAttachmentService;

    public ChatAttachmentSweepTask(ChatAttachmentService chatAttachmentService) {
        this.chatAttachmentService = chatAttachmentService;
    }

    @Scheduled(cron = "${solesonic.llm.attachment.sweep.cron}")
    public void sweepStagedAttachments() {
        log.debug("Sweeping staged attachments");

        chatAttachmentService.sweepStaged();
    }
}
