package com.solesonic.service.llm;

import com.solesonic.model.llm.LlmApiKey;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.util.Optional;

/**
 * Makes sure no model request leaves on the shared service key unless it was meant to.
 * <p>
 * Every OpenAI client in the application is built with the service key. A request that already names
 * another key — a streamed chat call carrying {@link LlmApiKey#authorizationHeaders()}, or a key being
 * checked before it is registered — passes untouched. One still on the service key is given the key of
 * the {@link LlmCredentialScope} it was made in, passes inside {@link LlmCredentialScope#asService},
 * and is otherwise refused: a call site that forgot whose turn it is fails loudly instead of quietly
 * spending on the shared key.
 */
public class UserLlmKeyInterceptor implements Interceptor {

    private final String serviceAuthorization;

    public UserLlmKeyInterceptor(LlmApiKey serviceApiKey) {
        this.serviceAuthorization = serviceApiKey.bearer();
    }

    @Override
    public @NonNull Response intercept(Interceptor.@NonNull Chain chain) throws IOException {
        Request request = chain.request();

        if (!serviceAuthorization.equals(request.header(HttpHeaders.AUTHORIZATION))) {
            return chain.proceed(request);
        }

        Optional<LlmApiKey> userKey = LlmCredentialScope.userKey();

        if (userKey.isPresent()) {
            return chain.proceed(request.newBuilder()
                    .header(HttpHeaders.AUTHORIZATION, userKey.get().bearer())
                    .build());
        }

        if (LlmCredentialScope.isService()) {
            return chain.proceed(request);
        }

        throw new IOException("Refused a model request made for no user: it would have been sent on the shared service key");
    }
}
