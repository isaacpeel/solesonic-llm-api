package com.solesonic.model.push;

/**
 * A notification button. The service worker only opens a same-origin path for it; it never calls an
 * endpoint, since it holds no access token.
 */
public record PushAction(String action, String title) {
}
