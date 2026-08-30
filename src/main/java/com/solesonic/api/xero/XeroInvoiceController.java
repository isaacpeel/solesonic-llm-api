package com.solesonic.api.xero;

import com.solesonic.model.xero.invoice.XeroInvoice;
import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.xero.XeroInvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating invoices in the connected Xero organisation.
 * <p>
 * Answers {@code 201 Created} even though Xero itself answers {@code 200}: this endpoint's contract
 * is this API's own, and a resource-creation endpoint here reports {@code 201}, as
 * {@code GeneratedImageController.generate} does. There is no {@code Location} to point at — this
 * application does not serve invoices back, and the created document lives in Xero.
 * <p>
 * The role gate is shared with the chat-callable tool that exposes the same capability, so the two
 * paths into a user's accounting system cannot be authorised differently by accident.
 */
@RestController
@RequestMapping("/xero")
public class XeroInvoiceController {
    private static final Logger log = LoggerFactory.getLogger(XeroInvoiceController.class);

    private final XeroInvoiceService xeroInvoiceService;
    private final UserRequestContext userRequestContext;

    public XeroInvoiceController(XeroInvoiceService xeroInvoiceService,
                                 UserRequestContext userRequestContext) {
        this.xeroInvoiceService = xeroInvoiceService;
        this.userRequestContext = userRequestContext;
    }

    @PostMapping(value = "/invoices", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ROLE_xero-invoice-create')")
    public ResponseEntity<XeroInvoice> create(@RequestBody XeroInvoiceRequest xeroInvoiceRequest) {
        log.info("Creating Xero invoice for user {}", userRequestContext.getUserId());

        XeroInvoice created = xeroInvoiceService.create(xeroInvoiceRequest, userRequestContext.getUserId());

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
