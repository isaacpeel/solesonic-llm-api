package com.solesonic.mcp.client;

import org.springframework.context.ApplicationEvent;

public class McpServerDisconnectedEvent extends ApplicationEvent {

    public McpServerDisconnectedEvent(Object source) {
        super(source);
    }
}
