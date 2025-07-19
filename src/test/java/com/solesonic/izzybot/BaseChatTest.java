package com.solesonic.izzybot;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

@SpringBootTest
public class BaseChatTest {
    @MockitoBean
    protected VectorStore pgVectorStore;

    @MockitoBean
    protected ChatClient chatClient;

    @Mock
    protected ChatResponse chatResponse;

    @Mock
    protected ChatClient.ChatClientRequestSpec chatClientRequestSpec;

    @Mock
    protected ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    protected ChatClient.AdvisorSpec advisorSpec;

    @Mock
    protected Generation generation;

    @Mock
    protected AssistantMessage assistantMessage;

    @BeforeEach
    public void beforeEach() {
        doAnswer(invocation -> List.of())
                .when(pgVectorStore)
                .similaritySearch(any(SearchRequest.class));

        doAnswer(invocation -> chatClientRequestSpec)
                .when(chatClient)
                .prompt();

        doAnswer(invocation -> chatClientRequestSpec)
                .when(chatClientRequestSpec)
                .user(any(String.class));


        doAnswer(invocation -> advisorSpec)
                .when(advisorSpec)
                .param(anyString(), any());

        doAnswer(invocation -> {
            Consumer<ChatClient.AdvisorSpec> advisorConsumer = invocation.getArgument(0);
            advisorConsumer.accept(advisorSpec);
            return chatClientRequestSpec;
        })
                .when(chatClientRequestSpec)
                .advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any());

        doCallRealMethod()
                .when(chatClientRequestSpec)
                .advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any());

        doAnswer(invocation -> chatClientRequestSpec)
                .when(chatClientRequestSpec)
                .tools(any(Object[].class));

        doAnswer(invocation -> callResponseSpec)
                .when(chatClientRequestSpec)
                .call();

        doAnswer(invocation -> chatClientRequestSpec)
                .when(chatClientRequestSpec)
                .messages(ArgumentMatchers.anyList());

        doAnswer(invocation -> chatResponse)
                .when(callResponseSpec)
                .chatResponse();

        doAnswer(invocation -> generation)
                .when(chatResponse)
                .getResult();

        doAnswer(invocation -> assistantMessage)
                .when(generation)
                .getOutput();
    }
}
