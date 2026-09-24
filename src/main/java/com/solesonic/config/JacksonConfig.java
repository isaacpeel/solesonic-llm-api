package com.solesonic.config;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.EventType;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.solesonic.config.agui.AgUiEventMixin;
import com.solesonic.config.agui.AgUiMessageMixin;
import com.solesonic.config.agui.AgUiWireValueMixin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .changeDefaultPropertyInclusion(incl ->
                        incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .addMixIn(Event.class, AgUiEventMixin.class)
                .addMixIn(Message.class, AgUiMessageMixin.class)
                .addMixIn(EventType.class, AgUiWireValueMixin.class)
                .addMixIn(Role.class, AgUiWireValueMixin.class)
                .build();
    }
}
