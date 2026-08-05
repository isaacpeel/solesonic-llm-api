package com.solesonic.model.image;

/**
 * The whole input surface of image generation. Size, steps, and the seed are fixed by the image
 * server and are deliberately not caller-tunable.
 */
public record GenerateImageRequest(String prompt) {
}
