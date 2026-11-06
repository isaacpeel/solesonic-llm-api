package com.solesonic.model.session;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SessionPromptView(UUID elicitationId,
                                String message,
                                Map<String, Object> requestedSchema,
                                Map<String, Object> meta,
                                List<UUID> forUserIds) {
}
