package com.solesonic.service.etl;

import com.solesonic.model.chat.attachment.ChatAttachmentIndexState;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatDocumentConsistencyReportTest {

    @Mock
    private ChatAttachmentService chatAttachmentService;

    @Mock
    private ChatDocumentIngestionService chatDocumentIngestionService;

    @InjectMocks
    private ChatDocumentConsistencyReport chatDocumentConsistencyReport;

    private static ChatAttachmentIndexState state(int chunkCount) {
        return new ChatAttachmentIndexState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "application/pdf", 100, chunkCount, null, false, null, null);
    }

    /**
     * The detector {@code V3_27}'s damage lacked: an attachment that claims chunks with nothing
     * behind them. Healthy and in-flight attachments are not listed.
     */
    @Test
    void test_lists_only_attachments_that_claim_chunks_but_have_none() {
        ChatAttachmentIndexState healthy = state(3);
        ChatAttachmentIndexState inFlight = state(3);
        ChatAttachmentIndexState broken = state(7);

        when(chatAttachmentService.documentsClaimingIndexed()).thenReturn(List.of(healthy, inFlight, broken));
        when(chatDocumentIngestionService.health(healthy)).thenReturn(ChatDocumentIngestionService.Health.HEALTHY);
        when(chatDocumentIngestionService.health(inFlight)).thenReturn(ChatDocumentIngestionService.Health.IN_FLIGHT);
        when(chatDocumentIngestionService.health(broken)).thenReturn(ChatDocumentIngestionService.Health.BROKEN);

        List<ChatDocumentConsistencyReport.BrokenAttachment> report = chatDocumentConsistencyReport.brokenAttachments();

        assertThat(report).containsExactly(
                new ChatDocumentConsistencyReport.BrokenAttachment(broken.id(), broken.chatId(), 7));
    }

    /**
     * A report, never a repair: it must not claim, heal, or write anything.
     */
    @Test
    void test_never_mutates() {
        ChatAttachmentIndexState broken = state(7);

        when(chatAttachmentService.documentsClaimingIndexed()).thenReturn(List.of(broken));
        when(chatDocumentIngestionService.health(broken)).thenReturn(ChatDocumentIngestionService.Health.BROKEN);

        chatDocumentConsistencyReport.logReport();

        verify(chatDocumentIngestionService, never()).heal(any(), any(), any());
        verify(chatAttachmentService, never()).claimIndexing(any(), any(), any(), any());
        verify(chatAttachmentService, never()).saveChunkCount(any(), anyInt());
        verify(chatAttachmentService, never()).saveChunkCountUnderLease(any(), anyInt(), any());
        verify(chatAttachmentService, never()).saveExtractionFailure(any(), any());
    }

    @Test
    void test_a_failing_scheduled_run_never_throws() {
        when(chatAttachmentService.documentsClaimingIndexed()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> chatDocumentConsistencyReport.logReport()).doesNotThrowAnyException();
    }
}
