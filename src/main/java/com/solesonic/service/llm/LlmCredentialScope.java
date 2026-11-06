package com.solesonic.service.llm;

import com.solesonic.model.llm.LlmApiKey;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Whose key the blocking model calls made inside a block carry, for the calls that have no
 * per-request header of their own: embeddings (which {@code PgVectorStore} makes through the shared
 * embedding model) and the Spring AI transformers the ETL pipeline runs.
 * <p>
 * A {@link ScopedValue} only reaches calls made on the binding thread. That holds for every blocking
 * OpenAI call — OkHttp runs its interceptors on the calling thread — and for nothing streamed, which
 * runs on the SDK's dispatcher. Streaming calls carry {@link LlmApiKey#authorizationHeaders()} on
 * their options instead.
 * <p>
 * {@link #asService} is the one way to send a request on the shared service key, and only work that
 * belongs to no user — the global corpus's ingestion — may use it. {@link UserLlmKeyInterceptor}
 * refuses a request on that key made outside both scopes.
 */
public final class LlmCredentialScope {
    private static final ScopedValue<LlmApiKey> USER_KEY = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> SERVICE = ScopedValue.newInstance();

    private LlmCredentialScope() {
    }

    public static <T> T asUser(LlmApiKey llmApiKey, Supplier<T> work) {
        return ScopedValue.where(USER_KEY, llmApiKey).call(work::get);
    }

    public static void runAsUser(LlmApiKey llmApiKey, Runnable work) {
        ScopedValue.where(USER_KEY, llmApiKey).run(work);
    }

    public static void runAsService(Runnable work) {
        ScopedValue.where(SERVICE, Boolean.TRUE).run(work);
    }

    public static <T> T asService(Supplier<T> work) {
        return ScopedValue.where(SERVICE, Boolean.TRUE).call(work::get);
    }

    public static Optional<LlmApiKey> userKey() {
        return USER_KEY.isBound() ? Optional.of(USER_KEY.get()) : Optional.empty();
    }

    static boolean isService() {
        return SERVICE.isBound();
    }
}
