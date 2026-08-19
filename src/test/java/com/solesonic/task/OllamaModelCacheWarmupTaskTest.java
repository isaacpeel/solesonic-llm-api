package com.solesonic.task;

import com.solesonic.service.ollama.OllamaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class OllamaModelCacheWarmupTaskTest {

    @Mock
    private OllamaService ollamaService;

    @InjectMocks
    private OllamaModelCacheWarmupTask ollamaModelCacheWarmupTask;

    @Test
    void testWarmCache() {
        ollamaModelCacheWarmupTask.warmCache();

        verify(ollamaService).refreshCache();
    }

    @Test
    void testWarmCacheSwallowsFailure() {
        doThrow(new IllegalStateException("ollama unreachable")).when(ollamaService).refreshCache();

        // A down Ollama server at boot must not fail application startup
        assertThatCode(() -> ollamaModelCacheWarmupTask.warmCache()).doesNotThrowAnyException();

        verify(ollamaService).refreshCache();
    }

    @Test
    void testWarmCacheOnStartupRunsOffTheStartupThread() throws InterruptedException {
        CountDownLatch refreshed = new CountDownLatch(1);
        AtomicReference<String> refreshThreadName = new AtomicReference<>();

        doAnswer(_ -> {
            refreshThreadName.set(Thread.currentThread().getName());
            refreshed.countDown();
            return null;
        }).when(ollamaService).refreshCache();

        ollamaModelCacheWarmupTask.warmCacheOnStartup();

        // The refresh is one HTTP call per installed model; readiness must not wait on them
        assertThat(refreshed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(refreshThreadName.get()).isNotEqualTo(Thread.currentThread().getName());
    }
}
