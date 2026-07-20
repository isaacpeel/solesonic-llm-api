package com.solesonic.service.etl;

import com.solesonic.exception.ChatException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UriContentFetcherTest {

    private static final String TEST_URI = "https://example.com/article";

    @Test
    void test_fetch_normalizes_content_type_with_charset() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockRestServiceServer.expect(requestTo(TEST_URI))
                .andRespond(withSuccess("<html>hello</html>", MediaType.parseMediaType("text/html; charset=utf-8")));

        UriContentFetcher uriContentFetcher = new UriContentFetcher(restClientBuilder.build(), DataSize.ofMegabytes(20));

        UriContentFetcher.FetchedContent fetchedContent = uriContentFetcher.fetch(TEST_URI);

        assertThat(fetchedContent.contentType()).isEqualTo("text/html");
        assertThat(fetchedContent.data()).isEqualTo("<html>hello</html>".getBytes());
    }

    @Test
    void test_fetch_defaults_to_html_when_content_type_missing() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockRestServiceServer.expect(requestTo(TEST_URI))
                .andRespond(withSuccess("<html>hello</html>", null));

        UriContentFetcher uriContentFetcher = new UriContentFetcher(restClientBuilder.build(), DataSize.ofMegabytes(20));

        UriContentFetcher.FetchedContent fetchedContent = uriContentFetcher.fetch(TEST_URI);

        assertThat(fetchedContent.contentType()).isEqualTo("text/html");
    }

    @Test
    void test_fetch_rejects_content_over_max_size() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        mockRestServiceServer.expect(requestTo(TEST_URI))
                .andRespond(withSuccess("x".repeat(50), MediaType.TEXT_PLAIN));

        UriContentFetcher uriContentFetcher = new UriContentFetcher(restClientBuilder.build(), DataSize.ofBytes(10));

        assertThatThrownBy(() -> uriContentFetcher.fetch(TEST_URI))
                .isInstanceOf(ChatException.class);
    }
}
