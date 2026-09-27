package com.solesonic.service.xero;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.exception.xero.XeroInvoiceValidationException;
import com.solesonic.model.xero.invoice.XeroInvoice;
import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.model.xero.invoice.XeroLineItemRequest;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.config.xero.XeroConstants.XERO_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;

/**
 * The create-invoice half of the Xero integration.
 * <p>
 * Two assertions are the reason this class exists. {@link #treatsHasErrorsInsideATwoHundredAsAFailure()}
 * pins the one place in this codebase where a {@code 200} means the call failed — Xero reports a
 * rejected invoice per-invoice rather than by status once {@code summarizeErrors=false} is set, so a
 * service that trusted the status line would tell a user their invoice exists when it does not. And
 * {@link #alwaysSetsTypeContactAndStatusItself()} plus
 * {@link #theCallerFacingRequestCannotCarryTypeContactOrStatus()} pin that those three fields are
 * policy, not input: every invoice this feature creates is a draft sales invoice billed to one
 * configured contact, and no request body may move any of that.
 */
class XeroInvoiceServiceTest {

    private static final String API_URI = "https://api.xero.com";
    private static final String INVOICES_URI = API_URI + "/api.xro/2.0/Invoices?summarizeErrors=false";

    private static final String DEFAULT_CONTACT_ID = "0d7a8f61-3c2e-4a55-9c9c-1f2f1c0b7e11";

    /**
     * A created invoice as Xero actually answers it: an envelope carrying fields this application
     * does not model, all-caps {@code ID} suffixes that {@code UpperCamelCaseStrategy} does not
     * produce on its own, and Microsoft-style dates rather than ISO ones.
     */
    private static final String CREATED_RESPONSE = """
            {"Id":"e0a9f3d1-6b5a-4a1d-9f7e-2a3c4d5e6f70","Status":"OK","ProviderName":"izzybot",
             "Invoices":[{
               "Type":"ACCREC",
               "InvoiceID":"9f8e7d6c-5b4a-3210-fedc-ba9876543210",
               "InvoiceNumber":"INV-0042",
               "Status":"DRAFT",
               "Contact":{"ContactID":"0d7a8f61-3c2e-4a55-9c9c-1f2f1c0b7e11","Name":"Demo Contact"},
               "LineItems":[{"Description":"Consulting","Quantity":2.0,"UnitAmount":150.00,
                             "LineAmount":300.00,"AccountCode":"200","TaxType":"OUTPUT",
                             "TaxAmount":45.00,"LineItemID":"11111111-2222-3333-4444-555555555555"}],
               "Date":"/Date(1756512000000+0000)/","DateString":"2026-08-30T00:00:00",
               "DueDate":"/Date(1759104000000+0000)/","DueDateString":"2026-09-29T00:00:00",
               "Reference":"PO-1234","CurrencyCode":"USD","LineAmountTypes":"Exclusive",
               "SubTotal":300.00,"TotalTax":45.00,"Total":345.00,
               "HasErrors":false}]}""";

    /**
     * What {@code summarizeErrors=false} buys: a {@code 200} whose invoice was not created, with the
     * reasons named per invoice instead of collapsed into a bare {@code 400}.
     */
    private static final String REJECTED_RESPONSE = """
            {"Id":"e0a9f3d1-6b5a-4a1d-9f7e-2a3c4d5e6f70","Status":"OK",
             "Invoices":[{
               "Type":"ACCREC","HasErrors":true,
               "ValidationErrors":[
                 {"Message":"Account code 'ZZZ' is not a valid code for this document."},
                 {"Message":"Invoice not of valid status for modification."}]}]}""";

    private static final String EMPTY_ENVELOPE = """
            {"Id":"e0a9f3d1-6b5a-4a1d-9f7e-2a3c4d5e6f70","Status":"OK","Invoices":[]}""";

    /** Built exactly as {@code JacksonConfig} builds the application's mapper. */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion ->
                    inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final ExchangeStrategies EXCHANGE_STRATEGIES = ExchangeStrategies.builder()
            .codecs(configurer -> {
                configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(JSON_MAPPER));
                configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(JSON_MAPPER));
            })
            .build();

    private static final BodyInserter.Context BODY_CONTEXT = new BodyInserter.Context() {
        @Override
        public @NonNull List<HttpMessageWriter<?>> messageWriters() {
            return EXCHANGE_STRATEGIES.messageWriters();
        }

        @Override
        public @NonNull Optional<ServerHttpRequest> serverRequest() {
            return Optional.empty();
        }

        @Override
        public @NonNull Map<String, Object> hints() {
            return Map.of();
        }
    };

    private final List<ClientRequest> recordedRequests = new ArrayList<>();

    private final UUID userId = UUID.randomUUID();

    private XeroInvoiceService xeroInvoiceService(String response) {
        WebClient apiWebClient = WebClient.builder()
                .baseUrl(API_URI)
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(JSON_MAPPER));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(JSON_MAPPER));
                })
                .exchangeFunction(request -> {
                    recordedRequests.add(request);

                    return Mono.just(ClientResponse.create(HttpStatus.OK, EXCHANGE_STRATEGIES)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(response)
                            .build());
                })
                .build();

        XeroInvoiceService xeroInvoiceService = new XeroInvoiceService(apiWebClient);

        ReflectionTestUtils.setField(xeroInvoiceService, "defaultContactId", DEFAULT_CONTACT_ID);

        return xeroInvoiceService;
    }

    private static XeroInvoiceRequest invoiceRequest() {
        XeroLineItemRequest lineItem = new XeroLineItemRequest(
                "Consulting",
                new BigDecimal("2"),
                new BigDecimal("150.00"),
                null,
                "200",
                null,
                "OUTPUT");

        return new XeroInvoiceRequest(
                List.of(lineItem),
                LocalDate.of(2026, 8, 30),
                LocalDate.of(2026, 9, 29),
                "PO-1234",
                "USD",
                "Exclusive");
    }

    /**
     * The user has to reach {@code XeroRequestAuthorizationFilter}, and the Reactor context is the
     * only channel that can carry it there: the filter runs inside the exchange, not on the caller's
     * thread, and the chat tool path has no request scope for it to read instead.
     */
    @Test
    void publishesTheUserIdIntoTheReactorContextForTheAuthorizationFilter() {
        List<UUID> observedUsers = new ArrayList<>();

        WebClient apiWebClient = WebClient.builder()
                .baseUrl(API_URI)
                .codecs(configurer -> {
                    configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(JSON_MAPPER));
                    configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(JSON_MAPPER));
                })
                .filter((request, next) -> Mono.deferContextual(contextView -> {
                    observedUsers.add(contextView.get(XERO_USER_ID));

                    return next.exchange(request);
                }))
                .exchangeFunction(_ -> Mono.just(ClientResponse.create(HttpStatus.OK, EXCHANGE_STRATEGIES)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(CREATED_RESPONSE)
                        .build()))
                .build();

        XeroInvoiceService xeroInvoiceService = new XeroInvoiceService(apiWebClient);
        ReflectionTestUtils.setField(xeroInvoiceService, "defaultContactId", DEFAULT_CONTACT_ID);

        xeroInvoiceService.create(invoiceRequest(), userId);

        assertThat(observedUsers).containsExactly(userId);
    }

    /**
     * A {@code null} user would be an unusable call — the filter could not name a connection to make
     * it on — and it is refused here rather than allowed to become a
     * {@link NullPointerException} inside Reactor's context, which carries no clue about its cause.
     */
    @Test
    void refusesToCreateAnInvoiceWithoutAUser() {
        XeroInvoiceService xeroInvoiceService = xeroInvoiceService(CREATED_RESPONSE);
        XeroInvoiceRequest invoiceRequest = invoiceRequest();

        assertThatThrownBy(() -> xeroInvoiceService.create(invoiceRequest, null))
                .isInstanceOf(XeroApiException.class);

        assertThat(recordedRequests).isEmpty();
    }

    /**
     * The whole reason {@code summarizeErrors=false} is on the query string. Xero answers {@code 200}
     * and reports the rejection inside the invoice, so the status line proves nothing — without this
     * check a caller would be told a draft exists in their accounting system when none does.
     */
    @Test
    void treatsHasErrorsInsideATwoHundredAsAFailure() {
        XeroInvoiceService xeroInvoiceService = xeroInvoiceService(REJECTED_RESPONSE);
        XeroInvoiceRequest invoiceRequest = invoiceRequest();

        assertThatThrownBy(() -> xeroInvoiceService.create(invoiceRequest, userId))
                .isInstanceOf(XeroInvoiceValidationException.class)
                .satisfies(thrown -> assertThat(((XeroInvoiceValidationException) thrown).getMessages())
                        .containsExactly(
                                "Account code 'ZZZ' is not a valid code for this document.",
                                "Invoice not of valid status for modification."));
    }

    @Test
    void postsToXerosInvoicesEndpointWithSummarizeErrorsFalse() {
        xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        assertThat(onlyRequest().method()).isEqualTo(POST);
        assertThat(onlyRequest().url().toString()).isEqualTo(INVOICES_URI);
    }

    /**
     * Type, contact and status are policy rather than input: every invoice created through this
     * feature is a draft sales invoice billed to the one configured contact. A human authorises it
     * in Xero's own UI afterwards.
     */
    @Test
    void alwaysSetsTypeContactAndStatusItself() {
        xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        JsonNode sentInvoice = onlySentInvoice();

        assertThat(sentInvoice.get("Type").asString()).isEqualTo("ACCREC");
        assertThat(sentInvoice.get("Status").asString()).isEqualTo("DRAFT");
        assertThat(sentInvoice.get("Contact").get("ContactID").asString()).isEqualTo(DEFAULT_CONTACT_ID);
    }

    /**
     * The other half of the guarantee above, and the one a future edit is most likely to break: there
     * must be nowhere in the caller-facing body for any of the three to be named at all.
     */
    @Test
    void theCallerFacingRequestCannotCarryTypeContactOrStatus() {
        List<String> componentNames = Arrays.stream(XeroInvoiceRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .containsExactlyInAnyOrder("lineItems", "date", "dueDate", "reference", "currencyCode",
                        "lineAmountTypes")
                .doesNotContain("type", "contact", "contactId", "status");
    }

    /**
     * Left unset so Xero applies the organisation's own invoice numbering. Supplying one risks a
     * collision with a number the org has already issued.
     */
    @Test
    void neverSendsAnInvoiceNumber() {
        xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        assertThat(onlySentInvoice().has("InvoiceNumber")).isFalse();
    }

    /** Xero takes and returns a bulk envelope even for a single invoice. */
    @Test
    void wrapsTheInvoiceInXerosBulkEnvelope() {
        xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        JsonNode body = sentBody();

        assertThat(body.has("Invoices")).isTrue();
        assertThat(body.get("Invoices").size()).isEqualTo(1);
    }

    /**
     * The caller's own fields, rendered in the PascalCase Xero expects. Dates go out ISO — the
     * Microsoft-style form Xero answers with is a read-side concern only.
     */
    @Test
    void sendsTheCallerSuppliedFieldsInXerosCasing() {
        xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        JsonNode sentInvoice = onlySentInvoice();

        assertThat(sentInvoice.get("Date").asString()).isEqualTo("2026-08-30");
        assertThat(sentInvoice.get("DueDate").asString()).isEqualTo("2026-09-29");
        assertThat(sentInvoice.get("Reference").asString()).isEqualTo("PO-1234");
        assertThat(sentInvoice.get("CurrencyCode").asString()).isEqualTo("USD");
        assertThat(sentInvoice.get("LineAmountTypes").asString()).isEqualTo("Exclusive");

        JsonNode sentLineItem = sentInvoice.get("LineItems").get(0);

        assertThat(sentLineItem.get("Description").asString()).isEqualTo("Consulting");
        assertThat(sentLineItem.get("Quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(sentLineItem.get("UnitAmount").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(sentLineItem.get("AccountCode").asString()).isEqualTo("200");
        assertThat(sentLineItem.get("TaxType").asString()).isEqualTo("OUTPUT");
    }

    /** Read-side fields have no business on the way out; a null one must be omitted, not sent null. */
    @Test
    void sendsNoReadSideFields() {
        xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        JsonNode sentInvoice = onlySentInvoice();

        assertThat(sentInvoice.has("InvoiceID")).isFalse();
        assertThat(sentInvoice.has("HasErrors")).isFalse();
        assertThat(sentInvoice.has("Total")).isFalse();
        assertThat(sentInvoice.has("DateString")).isFalse();
    }

    @Test
    void unwrapsTheSingleInvoiceFromTheResponseEnvelope() {
        XeroInvoice created = xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        assertThat(created.invoiceNumber()).isEqualTo("INV-0042");
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.total()).isEqualByComparingTo("345.00");
        assertThat(created.subTotal()).isEqualByComparingTo("300.00");
        assertThat(created.totalTax()).isEqualByComparingTo("45.00");
        assertThat(created.lineItems()).hasSize(1);
    }

    /**
     * {@code UpperCamelCaseStrategy} renders {@code invoiceId} as {@code InvoiceId}, but Xero sends
     * {@code InvoiceID}. Because {@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled, that mismatch is
     * silent — the id simply arrives null, and the created invoice becomes unfindable.
     */
    @Test
    void readsXerosAllCapsIdentifierFields() {
        XeroInvoice created = xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        assertThat(created.invoiceId()).isEqualTo("9f8e7d6c-5b4a-3210-fedc-ba9876543210");
        assertThat(created.contact().contactId()).isEqualTo(DEFAULT_CONTACT_ID);
        assertThat(created.lineItems().getFirst().lineItemId())
                .isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    /**
     * Xero's dates come back Microsoft-style, so they are carried as opaque strings rather than
     * parsed; the ISO companions Xero sends alongside are what a caller reads.
     */
    @Test
    void carriesXerosOwnDateRenderingsThroughUnparsed() {
        XeroInvoice created = xeroInvoiceService(CREATED_RESPONSE).create(invoiceRequest(), userId);

        assertThat(created.date()).isEqualTo("/Date(1756512000000+0000)/");
        assertThat(created.dateString()).isEqualTo("2026-08-30T00:00:00");
        assertThat(created.dueDateString()).isEqualTo("2026-09-29T00:00:00");
    }

    /**
     * Nothing to unwrap. Rendering this as anything but a failure would let
     * {@code GeneralExceptionHandler} answer {@code 200 OK} with a chat message, which a REST caller
     * reads as a successful creation.
     */
    @Test
    void refusesAnEnvelopeCarryingNoInvoice() {
        XeroInvoiceService xeroInvoiceService = xeroInvoiceService(EMPTY_ENVELOPE);
        XeroInvoiceRequest invoiceRequest = invoiceRequest();

        assertThatThrownBy(() -> xeroInvoiceService.create(invoiceRequest, userId))
                .isInstanceOf(XeroApiException.class);
    }

    private ClientRequest onlyRequest() {
        assertThat(recordedRequests).hasSize(1);

        return recordedRequests.getFirst();
    }

    private JsonNode onlySentInvoice() {
        return sentBody().get("Invoices").get(0);
    }

    /**
     * The assertion that matters is what actually went on the wire, not what was handed to the
     * builder — the naming strategy and the null-omission policy both only take effect at encode
     * time.
     */
    private JsonNode sentBody() {
        MockClientHttpRequest mockClientHttpRequest = new MockClientHttpRequest(POST, URI.create("/"));

        onlyRequest().body().insert(mockClientHttpRequest, BODY_CONTEXT).block();

        String body = mockClientHttpRequest.getBodyAsString().block();

        assertThat(body).isNotNull();

        return JSON_MAPPER.readTree(body.getBytes(StandardCharsets.UTF_8));
    }
}
