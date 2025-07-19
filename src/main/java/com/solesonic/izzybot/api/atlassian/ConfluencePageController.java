package com.solesonic.izzybot.api.atlassian;

import com.solesonic.izzybot.model.atlassian.confluence.ConfluencePagesResponse;
import com.solesonic.izzybot.model.atlassian.confluence.Page;
import com.solesonic.izzybot.service.atlassian.ConfluencePageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/confluence/pages")
public class ConfluencePageController {
    private final ConfluencePageService confluencePageService;

    public ConfluencePageController(ConfluencePageService confluencePageService) {
        this.confluencePageService = confluencePageService;
    }

    @GetMapping
    public ResponseEntity<ConfluencePagesResponse> getPages() {
        ConfluencePagesResponse response = confluencePageService.pages();

        return ResponseEntity.ok(response);
    }

    @GetMapping("{id}")
    public ResponseEntity<Page> get(@PathVariable String id) {
        Page response = confluencePageService.get(id);

        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Page> create(@RequestBody Page page) {
        // Call the service method to create the page with the Page object directly
        Page createdPage = confluencePageService.createPage(page);

        return ResponseEntity.status(HttpStatus.CREATED).body(createdPage);
    }

    @PutMapping("{id}")
    public ResponseEntity<Page> update(
            @PathVariable String id,
            @RequestBody Page page) {

        // Set the ID from the path parameter
        page.setId(id);

        // Call the service method to update the page with the Page object directly
        Page updatedPage = confluencePageService.updatePage(page);

        return ResponseEntity.ok(updatedPage);
    }

    @DeleteMapping("{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "false") boolean purge,
            @RequestParam(required = false, defaultValue = "false") boolean draft) {

        confluencePageService.deletePage(id, purge, draft);
        return ResponseEntity.noContent().build();
    }
}
