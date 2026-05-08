package com.solesonic.api.command;

import com.solesonic.model.prompt.SlashCommandCatalogResponse;
import com.solesonic.model.prompt.SlashCommand;
import com.solesonic.service.prompt.SlashCommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/slash")
public class SlashCommandController {
    private final SlashCommandService slashCommandService;

    public SlashCommandController(SlashCommandService slashCommandService) {
        this.slashCommandService = slashCommandService;
    }

    @GetMapping("/commands")
    public ResponseEntity<SlashCommandCatalogResponse> slashCommands(@RequestParam(name="command", required = false) String command) {
        List<SlashCommand> slashCommands = slashCommandService.typeAhead(command);
        SlashCommandCatalogResponse slashCommandCatalogResponse = new SlashCommandCatalogResponse(slashCommands);

        return ResponseEntity.ok(slashCommandCatalogResponse);
    }
}
