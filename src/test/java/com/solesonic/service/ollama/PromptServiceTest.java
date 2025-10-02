package com.solesonic.service.ollama;

import com.solesonic.model.ollama.OllamaModel;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.ollama.OllamaModelRepository;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.intent.IntentType;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.tools.confluence.CreateConfluenceTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class PromptServiceTest {

    @Mock
    private UserPreferencesService userPreferencesService;

    @Mock
    private UserRequestContext userRequestContext;

    @Mock
    private OllamaModelRepository ollamaModelRepository;

    @Mock
    private Resource jiraPrompt;

    @Mock
    private Resource confluencePrompt;

    @Mock
    private Resource basicPrompt;

    @Mock
    @SuppressWarnings("unused")
    private CreateConfluenceTools createConfluenceTools;

    @Mock
    @SuppressWarnings("unused")
    private VectorStore vectorStore;

    @Mock
    @SuppressWarnings("unused")
    private ChatClient chatClient;

    @Mock
    @SuppressWarnings("unused")
    private ChatResponse chatResponse;

    @Mock
    @SuppressWarnings("unused")
    private ChatClient.ChatClientRequestSpec chatClientRequestSpec;

    @Mock
    @SuppressWarnings("unused")
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    @SuppressWarnings("unused")
    private ChatClient.AdvisorSpec advisorSpec;

    @Mock
    @SuppressWarnings("unused")
    private Generation generation;

    @Mock
    @SuppressWarnings("unused")
    private AssistantMessage assistantMessage;

    private static final String BASIC_PROMPT_CONTENT = "This is a basic prompt template with {input}";
    private static final String TOOLS_PROMPT_CONTENT = "This is a tools prompt template with {input}";

    @InjectMocks
    private PromptService promptService;

    private UUID userId;
    private OllamaModel ollamaModel;

    @BeforeEach
    void setUp() throws Exception {
        userId = UUID.randomUUID();

        // Set up UserPreferences
        UserPreferences userPreferences = new UserPreferences();
        userPreferences.setUserId(userId);
        userPreferences.setModel("llama3");
        userPreferences.setSimilarityThreshold(0.7);

        // Set up OllamaModel
        ollamaModel = new OllamaModel();
        ollamaModel.setName("llama3");

        ReflectionTestUtils.setField(promptService, "defaultSimilarityThreshold", 0.5);

        ReflectionTestUtils.setField(promptService, "botName", "TestBot");

        lenient().when(basicPrompt.getInputStream()).thenReturn(new ByteArrayInputStream(BASIC_PROMPT_CONTENT.getBytes()));
        lenient().when(basicPrompt.getContentAsString(any())).thenReturn(BASIC_PROMPT_CONTENT);

        lenient().when(jiraPrompt.getInputStream()).thenReturn(new ByteArrayInputStream(TOOLS_PROMPT_CONTENT.getBytes()));
        lenient().when(jiraPrompt.getContentAsString(any())).thenReturn(TOOLS_PROMPT_CONTENT);

        lenient().when(confluencePrompt.getInputStream()).thenReturn(new ByteArrayInputStream(TOOLS_PROMPT_CONTENT.getBytes()));
        lenient().when(confluencePrompt.getContentAsString(any())).thenReturn(TOOLS_PROMPT_CONTENT);

        lenient().when(userRequestContext.getUserId()).thenReturn(userId);
        lenient().when(userPreferencesService.get(userId)).thenReturn(userPreferences);
        lenient().when(ollamaModelRepository.findByName("llama3")).thenReturn(Optional.of(ollamaModel));
    }

    @Test
    void testModel() {
        String model = promptService.model();

        assertThat(model).isEqualTo("llama3");
        verify(userRequestContext).getUserId();
        verify(userPreferencesService).get(userId);
        verify(userRequestContext).setChatModel("llama3");
    }

    @Test
    void testBuildTemplatePrompt_BasicPrompt() {
        String chatMessage = "Hello, how are you?";

        ReflectionTestUtils.setField(promptService, "basicPrompt", basicPrompt);

        Prompt result = promptService.buildTemplatePrompt(chatMessage, basicPrompt);

        assertThat(result).isNotNull();
    }

    @Test
    void testBuildTemplatePrompt_JiraPrompt() {
        String chatMessage = "Create a Jira issue for this bug";

        ReflectionTestUtils.setField(promptService, "jiraPrompt", jiraPrompt);

        Prompt result = promptService.buildTemplatePrompt(chatMessage, jiraPrompt);

        assertThat(result).isNotNull();
    }

    @Test
    void testBuildTemplatePrompt_ConfluencePrompt() {
        String chatMessage = "Create a Confluence page about this topic";

        ReflectionTestUtils.setField(promptService, "confluencePrompt", confluencePrompt);

        Prompt result = promptService.buildTemplatePrompt(chatMessage, confluencePrompt);

        assertThat(result).isNotNull();
    }


    @Test
    void testTools_WithToolsNeededAndModelSupportsTools() {
        String model = "llama3";
        IntentType intent = IntentType.CREATING_CONFLUENCE_PAGE;

        Map<String, Object> ollamaShowWithTools = Map.of(
                "capabilities", List.of("tools")
        );
        ollamaModel.setOllamaShow(ollamaShowWithTools);

        ToolCallback[] tools = promptService.tools(intent, model);

        assertThat(tools).isNotEmpty();
        verify(ollamaModelRepository).findByName(model);
    }

    @Test
    void testTools_WithToolsNeededButModelDoesNotSupportTools() {
        String model = "llama3";
        IntentType intent = IntentType.CREATING_JIRA_ISSUE;

        ToolCallback[] tools = promptService.tools(intent, model);

        assertThat(tools).isEmpty();
        verify(ollamaModelRepository).findByName(model);
    }

    @Test
    void testTools_WithoutToolsNeeded() {
        String model = "llama3";
        IntentType intent = IntentType.GENERAL;

        ToolCallback[] tools = promptService.tools(intent, model);

        assertThat(tools).isEmpty();
    }
}
