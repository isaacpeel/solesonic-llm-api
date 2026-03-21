package com.solesonic.api.prompt;

import com.solesonic.model.prompt.SlashCommandPrompt;
import com.solesonic.service.prompt.SlashCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlashCommandControllerTest {

    @Mock
    private SlashCommandService slashCommandService;

    @InjectMocks
    private SlashCommandController slashCommandController;

    @Test
    void shouldReturnSlashCommandCatalogResponse() {
        List<SlashCommandPrompt> slashCommands = List.of(
                new SlashCommandPrompt("/jira", "jira", "jira prompt"),
                new SlashCommandPrompt("/sports", "sports", "sports prompt")
        );
        when(slashCommandService.slashCommands()).thenReturn(slashCommands);

        var responseEntity = slashCommandController.slashCommands(null);

        assertThat(responseEntity.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(responseEntity.getBody()).isNotNull();
        assertThat(responseEntity.getBody().commands()).hasSize(2);
        assertThat(responseEntity.getBody().commands().getFirst().command()).isEqualTo("/jira");
    }
}
