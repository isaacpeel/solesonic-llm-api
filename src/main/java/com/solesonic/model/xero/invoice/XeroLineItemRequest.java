package com.solesonic.model.xero.invoice;

import java.math.BigDecimal;

/**
 * One line of a caller-supplied invoice.
 * <p>
 * Deliberately camelCase, unlike the {@code Xero*} models it is mapped onto: this is a body a client
 * of <em>this</em> API sends, and every other request body this application accepts is camelCase.
 * The PascalCase naming strategy belongs on the types that actually cross Xero's wire.
 * <p>
 * Xero needs {@code description} plus either {@code quantity} and {@code unitAmount} together, or a
 * bare {@code lineAmount}; {@code accountCode} must name a code in the organisation's own chart of
 * accounts. None of that is validated here — Xero validates it, and with
 * {@code summarizeErrors=false} it names each failure individually, which is a better message than
 * anything this application could invent.
 */
public record XeroLineItemRequest(
        String description,
        BigDecimal quantity,
        BigDecimal unitAmount,
        BigDecimal lineAmount,
        String accountCode,
        String itemCode,
        String taxType) {
}
