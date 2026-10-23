package com.solesonic.model.a2a;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LiteLlmAgent(
        @JsonProperty("identity") Object identity,
        @JsonProperty("identity_managed") Boolean identityManaged,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("execution_mode") String executionMode,
        @JsonProperty("jwt_auth_configured") Boolean jwtAuthConfigured,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("agent_name") String agentName,
        @JsonProperty("litellm_params") LiteLlmParams litellmParams,
        @JsonProperty("agent_card_params") AgentCardParams agentCardParams,
        @JsonProperty("object_permission") ObjectPermission objectPermission,
        @JsonProperty("spend") BigDecimal spend,
        @JsonProperty("tpm_limit") Integer tpmLimit,
        @JsonProperty("rpm_limit") Integer rpmLimit,
        @JsonProperty("session_tpm_limit") Integer sessionTpmLimit,
        @JsonProperty("session_rpm_limit") Integer sessionRpmLimit,
        @JsonProperty("static_headers") Map<String, Object> staticHeaders,
        @JsonProperty("extra_headers") Map<String, Object> extraHeaders,
        @JsonProperty("access_group_ids") List<String> accessGroupIds,
        @JsonProperty("kill_switch") Boolean killSwitch,
        @JsonProperty("keys") List<Object> keys,
        @JsonProperty("search_score") BigDecimal searchScore,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("updated_by") String updatedBy) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LiteLlmParams(
            @JsonProperty("is_public") Boolean isPublic) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentCardParams(
            @JsonProperty("url") String url,
            @JsonProperty("name") String name,
            @JsonProperty("skills") List<AgentSkill> skills,
            @JsonProperty("version") String version,
            @JsonProperty("provider") AgentProvider provider,
            @JsonProperty("security") List<Map<String, List<String>>> security,
            @JsonProperty("description") String description,
            @JsonProperty("capabilities") Capabilities capabilities,
            @JsonProperty("protocolVersion") String protocolVersion,
            @JsonProperty("securitySchemes") Map<String, SecurityScheme> securitySchemes,
            @JsonProperty("defaultInputModes") List<String> defaultInputModes,
            @JsonProperty("defaultOutputModes") List<String> defaultOutputModes,
            @JsonProperty("supportedInterfaces") List<AgentInterface> supportedInterfaces) {
    }

    /**
     * LiteLLM omits every capability it was not told about ({@code "capabilities": {}}), and the SDK's
     * {@link AgentCapabilities} takes primitives, which Jackson 3 refuses to default from absent.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(
            @JsonProperty("streaming") Boolean streaming,
            @JsonProperty("pushNotifications") Boolean pushNotifications,
            @JsonProperty("extendedAgentCard") Boolean extendedAgentCard) {

        public AgentCapabilities toAgentCapabilities() {
            return AgentCapabilities.builder()
                    .streaming(Boolean.TRUE.equals(streaming))
                    .pushNotifications(Boolean.TRUE.equals(pushNotifications))
                    .extendedAgentCard(Boolean.TRUE.equals(extendedAgentCard))
                    .build();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecurityScheme(
            @JsonProperty("type") String type,
            @JsonProperty("scheme") String scheme,
            @JsonProperty("description") String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectPermission(
            @JsonProperty("object_permission_id") String objectPermissionId,
            @JsonProperty("mcp_servers") List<Object> mcpServers,
            @JsonProperty("mcp_access_groups") List<Object> mcpAccessGroups,
            @JsonProperty("mcp_tool_permissions") Map<String, Object> mcpToolPermissions,
            @JsonProperty("vector_stores") List<Object> vectorStores,
            @JsonProperty("agents") List<Object> agents,
            @JsonProperty("agent_access_groups") List<Object> agentAccessGroups,
            @JsonProperty("models") List<Object> models,
            @JsonProperty("blocked_tools") List<Object> blockedTools,
            @JsonProperty("mcp_toolsets") List<Object> mcpToolsets,
            @JsonProperty("search_tools") List<Object> searchTools,
            @JsonProperty("mcp_tool_search_enabled") Boolean mcpToolSearchEnabled,
            @JsonProperty("skills") List<Object> skills,
            @JsonProperty("teams") List<Object> teams,
            @JsonProperty("projects") List<Object> projects,
            @JsonProperty("verification_tokens") List<Object> verificationTokens,
            @JsonProperty("organizations") List<Object> organizations,
            @JsonProperty("users") List<Object> users,
            @JsonProperty("end_users") List<Object> endUsers,
            @JsonProperty("agents_table") List<Object> agentsTable) {
    }
}