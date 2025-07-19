package com.solesonic.izzybot.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChatRequestTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    public void setup() {
        objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        objectMapper.findAndRegisterModules();
    }

    @Test
    public void testSerializeDeserialize() throws Exception {
        // Create a ChatRequest instance
        String originalMessage = "Hello, this is a test message";
        ChatRequest originalRequest = new ChatRequest(originalMessage);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(originalRequest);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("Serialized JSON: " + json);

        // Deserialize back to ChatRequest
        ChatRequest deserializedRequest = objectMapper.readValue(json, ChatRequest.class);

        // Verify the deserialized object
        assertNotNull(deserializedRequest);
        assertEquals(originalMessage, deserializedRequest.chatMessage());
    }

    @Test
    public void testSerializeDeserializeEmptyMessage() throws Exception {
        // Create a ChatRequest instance with empty message
        String originalMessage = "";
        ChatRequest originalRequest = new ChatRequest(originalMessage);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(originalRequest);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("Serialized JSON (empty message): " + json);

        // Deserialize back to ChatRequest
        ChatRequest deserializedRequest = objectMapper.readValue(json, ChatRequest.class);

        // Verify the deserialized object
        assertNotNull(deserializedRequest);
        assertEquals(originalMessage, deserializedRequest.chatMessage());
    }

    @Test
    public void testSerializeDeserializeSpecialCharacters() throws Exception {
        // Create a ChatRequest instance with special characters
        String originalMessage = "Special characters: !@#$%^&*()_+{}[]|\\:;\"'<>,.?/\nNew line and emoji 😊";
        ChatRequest originalRequest = new ChatRequest(originalMessage);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(originalRequest);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("Serialized JSON (special characters): " + json);

        // Deserialize back to ChatRequest
        ChatRequest deserializedRequest = objectMapper.readValue(json, ChatRequest.class);

        // Verify the deserialized object
        assertNotNull(deserializedRequest);
        assertEquals(originalMessage, deserializedRequest.chatMessage());
    }

    @Test
    public void testJsonStructure() throws Exception {
        // Create a ChatRequest instance
        String message = "Test message";
        ChatRequest request = new ChatRequest(message);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(request);

        // Verify JSON structure
        assertNotNull(json);
        System.out.println("JSON structure: " + json);

        // Verify that the JSON contains the expected field
        assertTrue(json.contains("\"chatMessage\""), "JSON should contain 'chatMessage' field");
        assertTrue(json.contains("\"" + message + "\""), "JSON should contain the message value");

        // Verify the JSON format (should be {"chatMessage":"Test message"})
        String expectedJson = "{\"chatMessage\":\"" + message + "\"}";
        assertEquals(expectedJson, json, "JSON structure should match expected format");
    }
}
