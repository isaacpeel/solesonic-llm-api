package com.solesonic.config.xero;

public class XeroConstants {
    public static final String XERO_AUTH_WEB_CLIENT = "xeroAuthWebClient";
    public static final String XERO_API_WEB_CLIENT = "xeroApiWebClient";

    /**
     * The Reactor subscription-context key naming the user an Accounting API call is made as.
     * <p>
     * A context key rather than the request scope, because the two entry points into invoice
     * creation do not share a thread. {@code XeroInvoiceController} runs on a servlet thread with an
     * HTTP request bound to it; {@code CreateXeroInvoiceTools} runs on the {@code boundedElastic}
     * worker the chat stream subscribes on, where nothing is bound at all. A Reactor context travels
     * with the subscription instead of the thread, so it is the one channel both paths can use.
     */
    public static final String XERO_USER_ID = "xeroUserId";

    private XeroConstants() {
    }
}
