package com.solesonic.service.chat;

import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.service.chat.LiteLlmModelInfoClient.ModelDisplaySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LiteLlmModelInfoClientTest {

    private static final String BASE_URL = "https://litellm.example.com/v1/";
    private static final String MODEL_INFO_URL = "https://litellm.example.com/v1/model/info";
    private static final LlmApiKey LLM_API_KEY = new LlmApiKey("sk-user-0000000001");

    private MockRestServiceServer mockRestServiceServer;
    private LiteLlmModelInfoClient liteLlmModelInfoClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        liteLlmModelInfoClient = new LiteLlmModelInfoClient(restClientBuilder, BASE_URL);
    }

    @Test
    void readsDisplayNameAndUiHiddenByLowerCasedModelNameWithTheUsersKey() {
        mockRestServiceServer.expect(requestTo(MODEL_INFO_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer sk-user-0000000001"))
                .andRespond(withSuccess("""
                        {"data": [
                          {"model_name": "Auto-Kev", "model_info": {"id": "1", "display_name": " auto ", "mode": "chat"}},
                          {"model_name": "nba-agent", "model_info": {"id": "2", "ui_hidden": true}},
                          {"model_name": "qwen3-embedding", "model_info": {"id": "3"}},
                          {"model_name": "no-info"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        Map<String, ModelDisplaySettings> settings = liteLlmModelInfoClient.displaySettings(LLM_API_KEY);

        assertThat(settings).containsOnly(
                Map.entry("auto-kev", new ModelDisplaySettings("auto", false)),
                Map.entry("nba-agent", new ModelDisplaySettings(null, true)),
                Map.entry("qwen3-embedding", new ModelDisplaySettings(null, false)),
                Map.entry("no-info", new ModelDisplaySettings(null, false)));
        mockRestServiceServer.verify();
    }

    /**
     * Several deployments behind one name: the first display name wins, and the name is hidden only when
     * every deployment is.
     */
    @Test
    void mergesTheDeploymentsBehindOneModelName() {
        mockRestServiceServer.expect(requestTo(MODEL_INFO_URL))
                .andRespond(withSuccess("""
                        {"data": [
                          {"model_name": "auto", "model_info": {"ui_hidden": true}},
                          {"model_name": "auto", "model_info": {"display_name": "Auto", "ui_hidden": false}},
                          {"model_name": "agent", "model_info": {"ui_hidden": true}},
                          {"model_name": "agent", "model_info": {"ui_hidden": true, "display_name": "Agent"}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        Map<String, ModelDisplaySettings> settings = liteLlmModelInfoClient.displaySettings(LLM_API_KEY);

        assertThat(settings).containsOnly(
                Map.entry("auto", new ModelDisplaySettings("Auto", false)),
                Map.entry("agent", new ModelDisplaySettings("Agent", true)));
    }

    /**
     * The admin UI's "LiteLLM Params" is where the keys often end up; {@code model_info} wins when both say.
     */
    @Test
    void readsTheKeysFromLitellmParamsWhenModelInfoHasNone() {
        mockRestServiceServer.expect(requestTo(MODEL_INFO_URL))
                .andRespond(withSuccess("""
                        {"data": [
                          {"model_name": "auto-kev", "litellm_params": {"model": "auto_router/complexity_router", "display_name": "auto"}, "model_info": {"id": "1"}},
                          {"model_name": "kev-serv", "litellm_params": {"model": "kev-serv", "ui_hidden": true}, "model_info": {"id": "2"}},
                          {"model_name": "both", "litellm_params": {"display_name": "params", "ui_hidden": true}, "model_info": {"display_name": "info", "ui_hidden": false}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        Map<String, ModelDisplaySettings> settings = liteLlmModelInfoClient.displaySettings(LLM_API_KEY);

        assertThat(settings).containsOnly(
                Map.entry("auto-kev", new ModelDisplaySettings("auto", false)),
                Map.entry("kev-serv", new ModelDisplaySettings(null, true)),
                Map.entry("both", new ModelDisplaySettings("info", false)));
    }

    @Test
    void anUnreadableEndpointGivesNoSettings() {
        mockRestServiceServer.expect(requestTo(MODEL_INFO_URL)).andRespond(withServerError());

        assertThat(liteLlmModelInfoClient.displaySettings(LLM_API_KEY)).isEmpty();
    }

    @Test
    void aUiHiddenThatIsNotABooleanGivesNoSettingsRatherThanHidingAModel() {
        mockRestServiceServer.expect(requestTo(MODEL_INFO_URL))
                .andRespond(withSuccess("""
                        {"data": [{"model_name": "auto", "model_info": {"ui_hidden": {"nested": true}}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(liteLlmModelInfoClient.displaySettings(LLM_API_KEY)).isEmpty();
    }
}
