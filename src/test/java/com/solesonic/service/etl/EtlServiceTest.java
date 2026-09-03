package com.solesonic.service.etl;

import com.solesonic.model.ingestion.DocumentStatus;
import com.solesonic.model.ingestion.IngestedDocument;
import com.solesonic.service.ingestion.IngestedDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one pipeline every scope's chunks pass through, and the status trail it leaves behind.
 * <p>
 * There are two overloads, and which one a caller reaches decides whether the document's progress is
 * visible at all: {@link EtlService#prepare(List, IngestedDocument)} writes the four
 * {@code status_history} transitions a client polls for, and {@link EtlService#prepare(List)} writes
 * none. Chat attachments were moved onto the tracked overload so that a chat document's progress and
 * failures are as visible as any upload's, which is only true for as long as the transitions keep
 * being emitted — nothing else in the suite asserts that they are.
 */
@ExtendWith(MockitoExtension.class)
class EtlServiceTest {

    @Mock
    private IngestedDocumentService ingestedDocumentService;

    @Mock
    private EtlKeywordEnricher etlKeywordEnricher;

    @Mock
    private EtlMetadataEnricher etlMetadataEnricher;

    @Mock
    private EtlTextSplitter etlTextSplitter;

    private EtlService etlService;

    private IngestedDocument ingestedDocument;

    @BeforeEach
    void beforeEach() {
        etlService = new EtlService(ingestedDocumentService,
                etlKeywordEnricher,
                etlMetadataEnricher,
                etlTextSplitter);

        ingestedDocument = new IngestedDocument();
        ingestedDocument.setId(UUID.randomUUID());
    }

    /**
     * The whole sequence, in order. A client watching a document ingest reads exactly these four
     * transitions, so an omission or a reordering is a visible regression rather than an internal
     * detail — and {@code PREPARING} in particular is what moves the row off {@code IN_PROGRESS}.
     */
    @Test
    void trackedPrepareEmitsEveryStatusTransitionInOrder() {
        List<Document> raw = List.of(new Document("raw text"));
        List<Document> split = List.of(new Document("split"));
        List<Document> keywordEnriched = List.of(new Document("keyword enriched"));
        List<Document> metadataEnriched = List.of(new Document("metadata enriched"));

        when(etlTextSplitter.split(raw)).thenReturn(split);
        when(etlKeywordEnricher.enrich(split)).thenReturn(keywordEnriched);
        when(etlMetadataEnricher.enrich(keywordEnriched)).thenReturn(metadataEnriched);

        List<Document> prepared = etlService.prepare(raw, ingestedDocument);

        assertThat(prepared).isEqualTo(metadataEnriched);

        InOrder inOrder = inOrder(ingestedDocumentService, etlTextSplitter, etlKeywordEnricher, etlMetadataEnricher);
        inOrder.verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.PREPARING);
        inOrder.verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.TOKEN_SPLITTING);
        inOrder.verify(etlTextSplitter).split(raw);
        inOrder.verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.KEYWORD_ENRICHING);
        inOrder.verify(etlKeywordEnricher).enrich(split);
        inOrder.verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.METADATA_ENRICHING);
        inOrder.verify(etlMetadataEnricher).enrich(keywordEnriched);
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * A stage that throws leaves the status trail at the stage that failed rather than advancing past
     * it. {@code DocumentService} is what turns that into {@code FAILED}; what matters here is that
     * nothing claims the later stages ran.
     * <p>
     * All three stages that can throw are covered, because the guarantee is about the pipeline rather
     * than about one enricher: a status written past a failure would leave the row describing work
     * that never happened.
     */
    @Test
    void trackedPrepareStopsAtAFailedKeywordEnrichment() {
        List<Document> raw = List.of(new Document("raw text"));
        List<Document> split = List.of(new Document("split"));

        when(etlTextSplitter.split(raw)).thenReturn(split);

        RuntimeException enrichmentFailure = new RuntimeException("keyword model unavailable");
        when(etlKeywordEnricher.enrich(split)).thenThrow(enrichmentFailure);

        assertThatThrownBy(() -> etlService.prepare(raw, ingestedDocument)).isSameAs(enrichmentFailure);

        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.PREPARING);
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.TOKEN_SPLITTING);
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.KEYWORD_ENRICHING);
        verify(ingestedDocumentService, never()).update(ingestedDocument, DocumentStatus.METADATA_ENRICHING);
        verify(ingestedDocumentService, never()).update(ingestedDocument, DocumentStatus.COMPLETED);
        verifyNoInteractions(etlMetadataEnricher);
    }

    @Test
    void trackedPrepareStopsAtAFailedSplit() {
        List<Document> raw = List.of(new Document("raw text"));

        RuntimeException splitFailure = new RuntimeException("splitter unavailable");
        when(etlTextSplitter.split(raw)).thenThrow(splitFailure);

        assertThatThrownBy(() -> etlService.prepare(raw, ingestedDocument)).isSameAs(splitFailure);

        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.PREPARING);
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.TOKEN_SPLITTING);
        verify(ingestedDocumentService, never()).update(ingestedDocument, DocumentStatus.KEYWORD_ENRICHING);
        verify(ingestedDocumentService, never()).update(ingestedDocument, DocumentStatus.METADATA_ENRICHING);
        verify(ingestedDocumentService, never()).update(ingestedDocument, DocumentStatus.COMPLETED);
        verifyNoInteractions(etlKeywordEnricher);
        verifyNoInteractions(etlMetadataEnricher);
    }

    /**
     * The last stage, which is the one where every status has already been written. Nothing may then
     * go on to claim the document {@code COMPLETED} — that call belongs to {@code DocumentService},
     * and only on the path where this method returned.
     */
    @Test
    void trackedPrepareStopsAtAFailedMetadataEnrichment() {
        List<Document> raw = List.of(new Document("raw text"));
        List<Document> split = List.of(new Document("split"));
        List<Document> keywordEnriched = List.of(new Document("keyword enriched"));

        when(etlTextSplitter.split(raw)).thenReturn(split);
        when(etlKeywordEnricher.enrich(split)).thenReturn(keywordEnriched);

        RuntimeException metadataFailure = new RuntimeException("metadata model unavailable");
        when(etlMetadataEnricher.enrich(keywordEnriched)).thenThrow(metadataFailure);

        assertThatThrownBy(() -> etlService.prepare(raw, ingestedDocument)).isSameAs(metadataFailure);

        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.PREPARING);
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.TOKEN_SPLITTING);
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.KEYWORD_ENRICHING);
        verify(ingestedDocumentService).update(ingestedDocument, DocumentStatus.METADATA_ENRICHING);
        verify(ingestedDocumentService, never()).update(ingestedDocument, DocumentStatus.COMPLETED);
    }

    /**
     * The untracked overload runs the same three processing steps and writes no status at all. It has
     * no {@code IngestedDocument} to write one against, which is exactly why routing a chat
     * attachment through it was what hid chat ingestion from {@code status_history} in the first
     * place.
     */
    @Test
    void untrackedPrepareRunsTheSameStepsAndWritesNoStatus() {
        List<Document> raw = List.of(new Document("raw text"));
        List<Document> split = List.of(new Document("split"));
        List<Document> keywordEnriched = List.of(new Document("keyword enriched"));
        List<Document> metadataEnriched = List.of(new Document("metadata enriched"));

        when(etlTextSplitter.split(raw)).thenReturn(split);
        when(etlKeywordEnricher.enrich(split)).thenReturn(keywordEnriched);
        when(etlMetadataEnricher.enrich(keywordEnriched)).thenReturn(metadataEnriched);

        assertThat(etlService.prepare(raw)).isEqualTo(metadataEnriched);

        verifyNoInteractions(ingestedDocumentService);
    }
}
