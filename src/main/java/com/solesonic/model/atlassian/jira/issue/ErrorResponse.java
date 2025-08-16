package com.solesonic.model.atlassian.jira.issue;

import java.util.List;

@SuppressWarnings("unused")
public record ErrorResponse(List<String> errorMessages, String errors, Integer status) {
}
