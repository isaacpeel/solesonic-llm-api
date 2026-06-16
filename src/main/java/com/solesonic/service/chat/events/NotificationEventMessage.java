package com.solesonic.service.chat.events;

public record NotificationEventMessage(String progressToken, String message, String progress, String total) {
}
