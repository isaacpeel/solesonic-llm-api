package com.solesonic.api.chat;

import com.solesonic.model.chat.attachment.ChatAttachment;
import com.solesonic.service.chat.attachment.ChatAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentControllerTest {
    private static final UUID ATTACHMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Mock
    private ChatAttachmentService chatAttachmentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatAttachmentController(chatAttachmentService)).build();
    }

    @Test
    void downloadServesWhatTheReadLookupAllows() throws Exception {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setContentType("image/png");
        chatAttachment.setFileData(new byte[]{1, 2, 3});
        when(chatAttachmentService.getReadable(ATTACHMENT_ID)).thenReturn(chatAttachment);

        mockMvc.perform(get("/attachments/{id}", ATTACHMENT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, private, immutable"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void anImageIsServedInlineAndNotSniffed() throws Exception {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setContentType("image/png");
        chatAttachment.setFileName("cat.png");
        chatAttachment.setFileData(new byte[]{1});
        when(chatAttachmentService.getReadable(ATTACHMENT_ID)).thenReturn(chatAttachment);

        mockMvc.perform(get("/attachments/{id}", ATTACHMENT_ID))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void aDocumentIsAlwaysADownloadWithASafeFilenameAndIsNotSniffed() throws Exception {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setContentType("text/html");
        chatAttachment.setFileName("evil\r\n\"<script>.html");
        chatAttachment.setFileData(new byte[]{1});
        when(chatAttachmentService.getReadable(ATTACHMENT_ID)).thenReturn(chatAttachment);

        mockMvc.perform(get("/attachments/{id}", ATTACHMENT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader("Content-Disposition");
                    org.assertj.core.api.Assertions.assertThat(disposition).startsWith("attachment;")
                            .doesNotContain("\r").doesNotContain("\n").doesNotContain("<script>");
                });
    }

    @Test
    void anAttachmentWithNoFilenameIsStillADownload() throws Exception {
        ChatAttachment chatAttachment = new ChatAttachment();
        chatAttachment.setContentType("application/pdf");
        chatAttachment.setFileData(new byte[]{1});
        when(chatAttachmentService.getReadable(ATTACHMENT_ID)).thenReturn(chatAttachment);

        mockMvc.perform(get("/attachments/{id}", ATTACHMENT_ID))
                .andExpect(header().string("Content-Disposition", "attachment"));
    }

    @Test
    void downloadOfAnAttachmentTheCallerMayNotReadIsNotFound() throws Exception {
        when(chatAttachmentService.getReadable(ATTACHMENT_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));

        mockMvc.perform(get("/attachments/{id}", ATTACHMENT_ID)).andExpect(status().isNotFound());
    }

    @Test
    void deleteGoesThroughTheUploaderOnlyPath() throws Exception {
        mockMvc.perform(delete("/attachments/{id}", ATTACHMENT_ID)).andExpect(status().isNoContent());

        verify(chatAttachmentService).delete(ATTACHMENT_ID);
    }
}
