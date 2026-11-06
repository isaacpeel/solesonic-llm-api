package com.solesonic.api.chat;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.model.chat.attachment.ChatAttachmentSummary;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import java.nio.charset.StandardCharsets;
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
        log.info("Uploading attachment.");
        ChatAttachmentSummary chatAttachmentSummary = chatAttachmentService.stage(file, description);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{attachmentId}")
                .buildAndExpand(chatAttachmentSummary.id())
                .toUri();

        return ResponseEntity.created(location).body(chatAttachmentSummary);
    }

    @GetMapping("/{attachmentId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID attachmentId) {
        log.info("Downloading attachment {}", attachmentId);

        ChatAttachment chatAttachment = chatAttachmentService.getReadable(attachmentId);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(chatAttachment.getContentType()))
                .header("X-Content-Type-Options", "nosniff")
                .eTag("\"" + attachmentId + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable());

        // The room reads what anyone in it uploaded. Images (the few types the vision pass accepts) render
        // inline; anything else is a download, so an uploaded HTML or SVG file is never rendered in the API's
        // own origin. The builder encodes the file name, which is the uploader's and proves nothing.
        if (!ChatAttachmentService.isImage(chatAttachment.getContentType())) {
            ContentDisposition.Builder disposition = ContentDisposition.attachment();

            if (chatAttachment.getFileName() != null && !chatAttachment.getFileName().isBlank()) {
                disposition.filename(safeFileName(chatAttachment.getFileName()), StandardCharsets.UTF_8);
            }

            response.header(HttpHeaders.CONTENT_DISPOSITION, disposition.build().toString());
        }

        return response.body(chatAttachment.getFileData());
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID attachmentId) {
        log.info("Deleting attachment: {}", attachmentId);
        chatAttachmentService.delete(attachmentId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Letters, digits, spaces and a few separators only: no quotes, angle brackets, slashes or control
     * characters, and a bounded length.
     */
    static String safeFileName(String fileName) {
        String safe = fileName.replaceAll("[^\\p{L}\\p{N} ._()\\-]", "_").strip();

        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
