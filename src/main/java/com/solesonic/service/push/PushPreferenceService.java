package com.solesonic.service.push;

import com.solesonic.exception.push.PushException;
import com.solesonic.model.push.PushErrorCode;
import com.solesonic.model.push.PushPreference;
import com.solesonic.model.push.PushPreferenceRequest;
import com.solesonic.model.push.PushPreferenceSummary;
import com.solesonic.repository.push.PushPreferenceRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.UUID;

@Service
public class PushPreferenceService {

    private final PushPreferenceRepository pushPreferenceRepository;
    private final Clock clock;

    public PushPreferenceService(PushPreferenceRepository pushPreferenceRepository, Clock clock) {
        this.pushPreferenceRepository = pushPreferenceRepository;
        this.clock = clock;
    }

    public PushPreferenceSummary get(UUID userId) {
        return pushPreferenceRepository.findById(userId)
                .map(PushPreferenceSummary::of)
                .orElse(PushPreferenceSummary.DEFAULT);
    }

    /**
     * Both fields are required: a client that does not know about one must not switch it off by
     * leaving it out.
     */
    public PushPreferenceSummary update(UUID userId, PushPreferenceRequest pushPreferenceRequest) {
        if (pushPreferenceRequest == null
                || pushPreferenceRequest.turnComplete() == null
                || pushPreferenceRequest.elicitation() == null) {
            throw new PushException(PushErrorCode.INVALID_PREFERENCES, "Both turnComplete and elicitation are required.");
        }

        PushPreference pushPreference = new PushPreference();
        pushPreference.setUserId(userId);
        pushPreference.setTurnComplete(pushPreferenceRequest.turnComplete());
        pushPreference.setElicitation(pushPreferenceRequest.elicitation());
        pushPreference.setUpdated(ZonedDateTime.now(clock));

        return PushPreferenceSummary.of(pushPreferenceRepository.save(pushPreference));
    }
}
