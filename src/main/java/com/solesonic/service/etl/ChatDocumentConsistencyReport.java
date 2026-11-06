package com.solesonic.service.etl;

import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lists chat document attachments that claim to be indexed but have nothing retrievable behind
 * them — the detector whose absence let {@code V3_27}'s damage go unnoticed for two months.
 * <p>
 * Read-only by design: it reports, it never repairs. Repair is the healer's job, on a schedule the
 * healer bounds. A detached attachment is not listed (it left its conversation on purpose), nor is
 * one whose refresh or ingest is in flight.
 * <p>
 * The scheduled run only logs, and is off unless
 * {@code solesonic.llm.attachment.consistency-report.cron} is set.
 */
@Service
public class ChatDocumentConsistencyReport {
    private static final Logger log = LoggerFactory.getLogger(ChatDocumentConsistencyReport.class);

    /**
     * @param chunkCount what the attachment claims, with nothing behind it
     */
    public record BrokenAttachment(UUID attachmentId, UUID chatId, Integer chunkCount) {
    }

    private final ChatAttachmentService chatAttachmentService;
    private final ChatDocumentIngestionService chatDocumentIngestionService;

    public ChatDocumentConsistencyReport(ChatAttachmentService chatAttachmentService,
                                         ChatDocumentIngestionService chatDocumentIngestionService) {
        this.chatAttachmentService = chatAttachmentService;
        this.chatDocumentIngestionService = chatDocumentIngestionService;
    }

    public List<BrokenAttachment> brokenAttachments() {
        List<BrokenAttachment> brokenAttachments = new ArrayList<>();

        for (ChatAttachmentIndexState indexState : chatAttachmentService.documentsClaimingIndexed()) {
            if (chatDocumentIngestionService.health(indexState) == ChatDocumentIngestionService.Health.BROKEN) {
                brokenAttachments.add(new BrokenAttachment(indexState.id(), indexState.chatId(), indexState.chunkCount()));
            }
        }

        return brokenAttachments;
    }

    @Scheduled(cron = "${solesonic.llm.attachment.consistency-report.cron:-}")
    public void logReport() {
        try {
            List<BrokenAttachment> brokenAttachments = brokenAttachments();

            if (brokenAttachments.isEmpty()) {
                log.info("Chat document consistency: every indexed attachment is retrievable");
                return;
            }

            log.warn("Chat document consistency: {} attachment(s) claim chunks but have none: {}",
                    brokenAttachments.size(), brokenAttachments);
        } catch (RuntimeException runtimeException) {
            log.warn("Chat document consistency report failed: {}", runtimeException.getMessage());
        }
    }
}
