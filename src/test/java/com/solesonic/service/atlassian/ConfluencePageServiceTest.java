package com.solesonic.service.atlassian;

import com.solesonic.model.atlassian.confluence.Body;
import com.solesonic.model.atlassian.confluence.ConfluencePagesResponse;
import com.solesonic.model.atlassian.confluence.Page;
import com.solesonic.model.atlassian.confluence.Storage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ConfluencePageServiceTest {

    @Mock
    private WebClient webClient;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    private ConfluencePageService confluencePageService;

    @BeforeEach
    public void setUp() {
        confluencePageService = new ConfluencePageService(webClient);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testGetPages() {
        // Prepare test data
        ConfluencePagesResponse pagesResponse = new ConfluencePagesResponse();
        Page page = new Page();
        page.setId("page-id-1");

        Body body = new Body();
        Storage storage = new Storage();
        storage.setValue("Page content");
        body.setStorage(storage);
        page.setBody(body);

        pagesResponse.setResults(List.of(page));

        // Mock WebClient for pages() method
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(pagesResponse));

        // Test pages() method
        ConfluencePagesResponse pages = confluencePageService.pages();

        // Verify results
        assertThat(pages).isNotNull();
        assertThat(pages.getResults()).allMatch(p -> p.getBody() != null);

        // Mock WebClient for get() method
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenReturn(Mono.just(page));

        // Test get() method for each page
        List<Page> results = pages.getResults();
        for (Page p : results) {
            Page singlePage = confluencePageService.get(p.getId());
            assertThat(singlePage).isNotNull();
        }
    }
}