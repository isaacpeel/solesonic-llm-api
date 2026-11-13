package com.solesonic.config.chat;

import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

/**
 * One bound on the model's memory: when to compact a session and how.
 */
public record SessionCompaction(CompactionTrigger trigger, CompactionStrategy strategy) {
}
