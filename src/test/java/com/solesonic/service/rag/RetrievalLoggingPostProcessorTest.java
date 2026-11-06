package com.solesonic.service.rag;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query is the user's own message. Logging it would copy chat content into {@code /var/log} and
 * the log agent's database, so this processor must log everything about retrieval except it.
 */
class RetrievalLoggingPostProcessorTest {

    private static final String SECRET_QUESTION = "what is my bank PIN 4417 in the attached statement";

    private final Logger logger = (Logger) LoggerFactory.getLogger(RetrievalLoggingPostProcessor.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void beforeEach() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void afterEach() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void logsWhatWasRetrievedButNeverTheQueryText() {
        Document document = Document.builder()
                .id("chunk-1")
                .text("statement text")
                .metadata(Map.of("INGESTED_DOCUMENT_ID", "doc-1", "distance", 0.42))
                .build();

        List<Document> processed = new RetrievalLoggingPostProcessor().process(new Query(SECRET_QUESTION), List.of(document));

        assertThat(processed).containsExactly(document);
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).doesNotContain("PIN").doesNotContain(SECRET_QUESTION);
        });
        assertThat(appender.list).anySatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("chunk-1").contains("doc-1"));
    }

    @Test
    void logsNothingAboveDebug() {
        logger.setLevel(Level.INFO);

        new RetrievalLoggingPostProcessor().process(new Query(SECRET_QUESTION), List.of());

        assertThat(appender.list).isEmpty();
    }
}
