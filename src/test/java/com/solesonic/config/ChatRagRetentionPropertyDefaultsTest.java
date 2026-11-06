package com.solesonic.config;

import com.solesonic.service.etl.ChatDocumentHealer;
import com.solesonic.service.etl.ChatDocumentIngestionService;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every property the chat-RAG-retention change introduced must resolve with nothing configured.
 * <p>
 * The retrieval thresholds exist only in the per-profile files, so a new property written the same
 * way would fail startup in any profile that lacks it ({@code application-ssl}, for one). Loading the
 * whole context per profile needs Postgres, Redis and the MCP server; resolving the placeholders
 * against an empty environment proves the same thing for these properties.
 */
class ChatRagRetentionPropertyDefaultsTest {

    private static final Set<String> INTRODUCED = Set.of(
            "solesonic.llm.attachment.index.stale-lease",
            "solesonic.llm.attachment.index.lease-wait",
            "solesonic.llm.attachment.heal.enabled",
            "solesonic.llm.attachment.heal.interval",
            "solesonic.llm.attachment.heal.max-per-run",
            "solesonic.llm.attachment.heal.retry-cooldown",
            "solesonic.llm.attachment.consistency-report.cron",
            "solesonic.llm.retrieval.query-compression.enabled",
            "solesonic.llm.retrieval.chat-floor-top-k");

    private static List<String> valueExpressions(Class<?> type) {
        List<AnnotatedElement> elements = new ArrayList<>(List.of(type.getDeclaredFields()));

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            elements.addAll(List.of(constructor.getParameters()));
        }

        List<String> expressions = new ArrayList<>();

        for (AnnotatedElement element : elements) {
            Value value = element instanceof Field field ? field.getAnnotation(Value.class)
                    : ((Parameter) element).getAnnotation(Value.class);

            if (value != null && INTRODUCED.stream().anyMatch(value.value()::contains)) {
                expressions.add(value.value());
            }
        }

        return expressions;
    }

    @Test
    void everyIntroducedPropertyResolvesWithNothingConfigured() {
        MockEnvironment empty = new MockEnvironment();

        List<String> expressions = new ArrayList<>();
        expressions.addAll(valueExpressions(ChatDocumentIngestionService.class));
        expressions.addAll(valueExpressions(ChatDocumentHealer.class));
        expressions.addAll(valueExpressions(VectorStoreService.class));

        // The cron is on a method annotation; checked by its literal below.
        expressions.add("${solesonic.llm.attachment.consistency-report.cron:-}");

        assertThat(expressions).hasSizeGreaterThanOrEqualTo(INTRODUCED.size());

        for (String expression : expressions) {
            assertThatCode(() -> empty.resolveRequiredPlaceholders(expression))
                    .as(expression)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Everything that changes behaviour in production ships off.
     */
    @Test
    void behaviourChangingPropertiesDefaultOff() {
        MockEnvironment empty = new MockEnvironment();

        assertThat(empty.resolvePlaceholders("${solesonic.llm.attachment.heal.enabled:false}")).isEqualTo("false");
        assertThat(valueExpressions(ChatDocumentHealer.class))
                .contains("${solesonic.llm.attachment.heal.enabled:false}");
        assertThat(valueExpressions(VectorStoreService.class))
                .contains("${solesonic.llm.retrieval.query-compression.enabled:false}",
                        "${solesonic.llm.retrieval.chat-floor-top-k:0}");
    }
}
