package com.solesonic.model.xero.invoice;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One reason Xero rejected an invoice, as reported inside a {@code 200} when
 * {@code summarizeErrors=false} is set.
 * <p>
 * Unlike an OAuth error body, this text is safe to return to a caller: it is accounting validation
 * wording written for the person who submitted the document ("Account code 'ZZZ' is not a valid code
 * for this document"), naming nothing about the client, the tenant or the token.
 */
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public record XeroValidationError(String message) {
}
