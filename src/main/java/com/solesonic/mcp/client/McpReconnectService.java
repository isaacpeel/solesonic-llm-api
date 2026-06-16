package com.solesonic.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "solesonic.mcp.reconnect.enabled", havingValue = "true")
public class McpReconnectService {
    private static final Logger log = LoggerFactory.getLogger(McpReconnectService.class);

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final Duration MAX_RECONNECT_DURATION = Duration.ofHours(5);

    private final McpSyncClient mcpSyncClient;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    public McpReconnectService(McpSyncClient mcpSyncClient) {
        this.mcpSyncClient = mcpSyncClient;
    }

    @EventListener
    @SuppressWarnings("unused")
    public void onMcpServerDisconnected(McpServerDisconnectedEvent event) {
        if (!reconnecting.compareAndSet(false, true)) {
            log.debug("MCP reconnect already in progress, skipping duplicate disconnect event.");
            return;
        }

        log.info("MCP server disconnected. Starting reconnect loop with exponential backoff.");
        Thread.ofVirtual().start(this::runReconnectLoop);
    }

    @SuppressWarnings("BusyWait")
    private void runReconnectLoop() {
        Duration currentBackoff = INITIAL_BACKOFF;
        long startTime = System.currentTimeMillis();
        int attemptCount = 0;

        try {
            while (elapsed(startTime) < MAX_RECONNECT_DURATION.toMillis()) {
                attemptCount++;
                log.info("MCP reconnect attempt {} (next backoff: {}s)", attemptCount, currentBackoff.toSeconds());

                try {
                    mcpSyncClient.ping();
                    log.info("MCP server reconnected successfully after {} attempt(s).", attemptCount);
                    return;
                } catch (Exception exception) {
                    log.warn("MCP reconnect attempt {} failed: {}", attemptCount, exception.getMessage());
                }

                long remainingMillis = MAX_RECONNECT_DURATION.toMillis() - elapsed(startTime);

                if (remainingMillis <= 0) {
                    break;
                }

                long sleepMillis = Math.min(currentBackoff.toMillis(), remainingMillis);

                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    log.warn("MCP reconnect loop interrupted after {} attempt(s).", attemptCount);
                    return;
                }

                Duration doubledBackoff = currentBackoff.multipliedBy(2);

                if (doubledBackoff.compareTo(MAX_BACKOFF) > 0) {
                    currentBackoff = MAX_BACKOFF;
                } else {
                    currentBackoff = doubledBackoff;
                }
            }

            log.error("MCP server did not reconnect within {} hours after {} attempt(s). Giving up.",
                    MAX_RECONNECT_DURATION.toHours(), attemptCount);
        } finally {
            reconnecting.set(false);
        }
    }

    private long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }
}
