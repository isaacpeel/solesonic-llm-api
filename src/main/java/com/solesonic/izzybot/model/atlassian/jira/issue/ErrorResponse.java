package com.solesonic.izzybot.model.atlassian.jira.issue;

import java.util.List;

public record ErrorResponse(List<String> errorMessages, String errors, Integer status) {
}
