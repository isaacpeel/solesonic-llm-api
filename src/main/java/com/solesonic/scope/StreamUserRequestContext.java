package com.solesonic.scope;

import java.util.UUID;

public class StreamUserRequestContext {
    private static final ThreadLocal<String> chatModel = new ThreadLocal<>();
    private static final ThreadLocal<UUID> userId = new ThreadLocal<>();

    public static void setChatModel(String model) {
        chatModel.set(model);
    }

    public static String getChatModel() {
        return chatModel.get();
    }

    public static void setUserId(UUID id) {
        userId.set(id);
    }

    public static UUID getUserId() {
        return userId.get();
    }

    public static void clear() {
        chatModel.remove();
        userId.remove();
    }
}
