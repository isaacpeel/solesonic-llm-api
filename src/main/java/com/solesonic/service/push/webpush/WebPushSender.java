package com.solesonic.service.push.webpush;

import com.solesonic.model.push.PushDeliveryOptions;
import com.solesonic.model.push.PushDeliveryResult;
import com.solesonic.model.push.PushTarget;

/**
 * Sends one encrypted push. Every status the push service answers comes back as a result, never as
 * an exception; a transport failure surfaces as a {@code RestClientException}.
 */
public interface WebPushSender {

    PushDeliveryResult send(PushTarget target, byte[] payload, PushDeliveryOptions options);
}
