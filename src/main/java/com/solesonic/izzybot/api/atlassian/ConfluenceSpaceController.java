package com.solesonic.izzybot.api.atlassian;

import com.solesonic.izzybot.model.atlassian.confluence.Space;
import com.solesonic.izzybot.model.atlassian.confluence.SpacesResponse;
import com.solesonic.izzybot.service.atlassian.ConfluenceSpaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/confluence/spaces")
public class ConfluenceSpaceController {
    private final ConfluenceSpaceService confluenceSpaceService;

    public ConfluenceSpaceController(ConfluenceSpaceService confluenceSpaceService) {
        this.confluenceSpaceService = confluenceSpaceService;
    }

    @GetMapping
    public ResponseEntity<SpacesResponse> getSpaces() {
        SpacesResponse response = confluenceSpaceService.getSpaces();
        return ResponseEntity.ok(response);
    }

    @GetMapping("{id}")
    public ResponseEntity<Space> getSpace(@PathVariable String id) {
        Space response = confluenceSpaceService.getSpace(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Space> createSpace(@RequestBody Space space) {
        Space response = confluenceSpaceService.createSpace(space);
        return ResponseEntity.ok(response);
    }
}