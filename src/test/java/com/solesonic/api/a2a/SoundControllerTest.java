package com.solesonic.api.a2a;

import com.solesonic.service.a2a.A2ASoundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SoundControllerTest {
    private static final String SOUND_ID = "3c1b5f9b0ac0c1d4c6d2b6e0b2b0e4c5c6d0e7f2a9b8c7d6e5f4a3b2c1d0e9f8";
    private static final byte[] CLIP = "ID3-fake-mp3".getBytes(StandardCharsets.UTF_8);

    @Mock
    private A2ASoundService soundService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SoundController(soundService)).build();
    }

    @SuppressWarnings("all")
    private MvcResult started(String soundId) throws Exception {
        return mockMvc.perform(get("/sounds/{soundId}", soundId))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    void aStoredSoundIsServedImmutableWithItsType() throws Exception {
        when(soundService.load(SOUND_ID)).thenReturn(Mono.just(new A2ASoundService.StoredSound(CLIP, "audio/mpeg")));

        mockMvc.perform(asyncDispatch(started(SOUND_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mpeg"))
                .andExpect(header().string("ETag", "\"" + SOUND_ID + "\""))
                .andExpect(header().string("Cache-Control", "max-age=31536000, private, immutable"))
                .andExpect(content().bytes(CLIP));
    }

    @Test
    void anUnknownOrExpiredSoundIsNotFound() throws Exception {
        when(soundService.load(SOUND_ID)).thenReturn(Mono.empty());

        mockMvc.perform(asyncDispatch(started(SOUND_ID)))
                .andExpect(status().isNotFound());
    }
}
