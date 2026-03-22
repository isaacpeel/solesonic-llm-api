package com.solesonic.model.prompt;

import java.util.List;

public record SlashCommandCatalogResponse(List<SlashCommandPrompt> commands) {
}
