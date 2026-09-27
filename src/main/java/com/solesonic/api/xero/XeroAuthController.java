package com.solesonic.api.xero;

import com.solesonic.model.xero.auth.XeroAuthLinkResponse;
import com.solesonic.service.xero.XeroAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connect flow for a Xero organisation. Both endpoints are authenticated like every other route
 * in this application: the callback deliberately takes the user from its own request rather than
 * from the {@code state} it is handed.
 */
@RestController
@RequestMapping("/xero")
public class XeroAuthController {
    private final XeroAuthService xeroAuthService;

    public XeroAuthController(XeroAuthService xeroAuthService) {
        this.xeroAuthService = xeroAuthService;
    }

    @GetMapping("/auth/uri")
    public ResponseEntity<XeroAuthLinkResponse> getXeroAuthUri() {
        String authUri = xeroAuthService.authUri();
        XeroAuthLinkResponse xeroAuthLinkResponse = new XeroAuthLinkResponse(authUri);

        return ResponseEntity.ok(xeroAuthLinkResponse);
    }

    @GetMapping("/auth/callback")
    public ResponseEntity<Void> xeroCallback(@RequestParam String code) {
        xeroAuthService.callback(code);

        return ResponseEntity.noContent().build();
    }
}
