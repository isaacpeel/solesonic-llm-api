package com.solesonic.model.xero.invoice;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Xero's bulk wrapper, {@code {"Invoices": [...]}}. There is no single-invoice form of
 * {@code POST /api.xro/2.0/Invoices} — the envelope is required even for one invoice, on the way out
 * and on the way back.
 * <p>
 * Xero sends {@code Id}, {@code Status} and {@code ProviderName} at this level too; they are dropped
 * rather than modelled, which works because {@code JacksonConfig} disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}. Note that the envelope's {@code Status} says the
 * <em>request</em> was processed and says nothing about whether the invoice inside it was accepted —
 * that answer lives on {@link XeroInvoice#hasErrors()}.
 */
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public record XeroInvoicesEnvelope(List<XeroInvoice> invoices) {
}
