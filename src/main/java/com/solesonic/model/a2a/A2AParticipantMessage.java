package com.solesonic.model.a2a;

import java.util.List;
import java.util.Map;

/**
 * One message in a shared session, as the agent is sent it: the message itself (typed {@code text}, or
 * the {@code answerContent} of the question the session waits on), who sent it, and everyone taking
 * part. Exactly one of {@code text} and {@code answerContent} is set.
 */
public record A2AParticipantMessage(String text,
                                    Map<String, Object> answerContent,
                                    String senderName,
                                    List<String> participants) {

    public static A2AParticipantMessage typed(String text, String senderName, List<String> participants) {
        return new A2AParticipantMessage(text, null, senderName, participants);
    }

    public static A2AParticipantMessage answer(Map<String, Object> answerContent, String senderName, List<String> participants) {
        return new A2AParticipantMessage(null, answerContent, senderName, participants);
    }

    public boolean isAnswer() {
        return answerContent != null;
    }
}
