package com.solesonic.mcp.client;

class McpAuthExchangeFilterTest {

//    private ObjectMapper objectMapper;
//    private McpAuthExchangeFilter filter;
//    private Function<String, Mono<String>> tokenResolver;
//
//    @BeforeEach
//    void setUp() {
//        objectMapper = new ObjectMapper();
//        tokenResolver = cacheKey -> Mono.just("obo-token-for-" + cacheKey);
//        filter = McpAuthExchangeFilter.of(objectMapper, tokenResolver);
//    }
//
//    @Test
//    void shouldApplyAuthorizationHeaderWhenCacheKeyPresent() {
//        String jsonBody = """
//                {
//                    "params": {
//                        "meta": {
//                            "cache_key": "user-123"
//                        },
//                        "other": "data"
//                    }
//                }
//                """;
//
//        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/mcp"))
//                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//                .body(BodyInserters.fromValue(jsonBody))
//                .build();
//
//        ExchangeFunction mockExchangeFunction = req -> {
//            assertThat(req.headers().getFirst(HttpHeaders.AUTHORIZATION))
//                    .isEqualTo("Bearer obo-token-for-user-123");
//
//            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
//        };
//
//        ClientResponse response = filter.filter(request, mockExchangeFunction).block();
//
//        assertThat(response).isNotNull();
//    }
//
//    @Test
//    void shouldPassThroughWhenContentTypeIsNotJson() {
//        String textBody = "plain text content";
//
//        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/mcp"))
//                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
//                .body(BodyInserters.fromValue(textBody))
//                .build();
//
//        ExchangeFunction mockExchangeFunction = req -> {
//            assertThat(req.headers().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
//
//            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
//        };
//
//        ClientResponse response = filter.filter(request, mockExchangeFunction).block();
//
//        assertThat(response).isNotNull();
//    }
//
//    @Test
//    void shouldPassThroughWhenCacheKeyMissing() {
//        String jsonBody = """
//                {
//                    "params": {
//                        "other": "data"
//                    }
//                }
//                """;
//
//        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/mcp"))
//                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//                .body(BodyInserters.fromValue(jsonBody))
//                .build();
//
//        ExchangeFunction mockExchangeFunction = req -> {
//            assertThat(req.headers().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
//
//            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
//        };
//
//        ClientResponse response = filter.filter(request, mockExchangeFunction).block();
//
//        assertThat(response).isNotNull();
//    }
//
//    @Test
//    void shouldPassThroughWhenCacheKeyEmpty() {
//        String jsonBody = """
//                {
//                    "params": {
//                        "meta": {
//                            "cache_key": ""
//                        }
//                    }
//                }
//                """;
//
//        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/mcp"))
//                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//                .body(BodyInserters.fromValue(jsonBody))
//                .build();
//
//        ExchangeFunction mockExchangeFunction = req -> {
//            assertThat(req.headers().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
//
//            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
//        };
//
//        ClientResponse response = filter.filter(request, mockExchangeFunction).block();
//
//        assertThat(response).isNotNull();
//    }
//
//    @Test
//    void shouldPassThroughWhenTokenResolutionFails() {
//        Function<String, Mono<String>> failingResolver = cacheKey ->
//                Mono.error(new RuntimeException("Token service unavailable"));
//
//        McpAuthExchangeFilter filterWithFailingResolver = McpAuthExchangeFilter.of(objectMapper, failingResolver);
//
//        String jsonBody = """
//                {
//                    "params": {
//                        "meta": {
//                            "cache_key": "user-456"
//                        }
//                    }
//                }
//                """;
//
//        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/mcp"))
//                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//                .body(BodyInserters.fromValue(jsonBody))
//                .build();
//
//        ExchangeFunction mockExchangeFunction = req -> {
//            assertThat(req.headers().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
//
//            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
//        };
//
//        ClientResponse response = filterWithFailingResolver.filter(request, mockExchangeFunction).block();
//
//        assertThat(response).isNotNull();
//    }
//
//    @Test
//    void shouldPreserveOriginalRequestBody() {
//        String jsonBody = """
//                {
//                    "params": {
//                        "meta": {
//                            "cache_key": "user-789"
//                        },
//                        "data": "important-data"
//                    }
//                }
//                """;
//
//        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/mcp"))
//                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//                .body(BodyInserters.fromValue(jsonBody))
//                .build();
//
//        ExchangeFunction mockExchangeFunction = req -> {
//            assertThat(req.headers().getFirst(HttpHeaders.AUTHORIZATION))
//                    .isEqualTo("Bearer obo-token-for-user-789");
//
//            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
//        };
//
//        ClientResponse response = filter.filter(request, mockExchangeFunction).block();
//
//        assertThat(response).isNotNull();
//    }
//
//    @Test
//    void shouldHandleNestedJsonStructure() {
//        String jsonBody = """
//                {
//                    "jsonrpc": "2.0",
//                    "method": "tools/call",
//                    "params": {
//                        "name": "get_weather",
//                        "meta": {
//                            "cache_key": "session-abc-123",
//                            "timestamp": 1234567890
//                        },
//                        "arguments": {
//                            "city": "San Francisco"
//                        }
//                    },
//                    "id": 1
//                }
//                """;
//
//        ClientRequest request = ClientRequest.create(HttpMethod.POST, URI.create("http://localhost/mcp"))
//                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
//                .body(BodyInserters.fromValue(jsonBody))
//                .build();
//
//        ExchangeFunction mockExchangeFunction = req -> {
//            assertThat(req.headers().getFirst(HttpHeaders.AUTHORIZATION))
//                    .isEqualTo("Bearer obo-token-for-session-abc-123");
//
//            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
//        };
//
//        ClientResponse response = filter.filter(request, mockExchangeFunction).block();
//
//        assertThat(response).isNotNull();
//    }
}
