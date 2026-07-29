package com.solesonic.api.chat;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/attachments")
public class ChatAttachmentController {
    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentController.class);

    private final ChatAttachmentService chatAttachmentService;

    public ChatAttachmentController(ChatAttachmentService chatAttachmentService) {
        this.chatAttachmentService = chatAttachmentService;
    }

    @PostMapping
    public ResponseEntity<ChatAttachmentSummary> upload(@RequestParam MultipartFile file,
                                                        @RequestParam(required = false) String description) {
        ChatAttachmentSummary chatAttachmentSummary = chatAttachmentService.stage(file, description);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{attachmentId}")
                .buildAndExpand(chatAttachmentSummary.id())
                .toUri();

        return ResponseEntity.created(location).body(chatAttachmentSummary);
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID attachmentId) {
        log.debug("Downloading attachment {}", attachmentId);

        ChatAttachment chatAttachment = chatAttachmentService.get(attachmentId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(chatAttachment.getContentType()))
                .eTag("\"" + attachmentId + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(chatAttachment.getFileData());
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID attachmentId) {
        chatAttachmentService.delete(attachmentId);

        return ResponseEntity.noContent().build();
    }
}
