package com.solesonic.model.image;

/**
 * One image as the image service returned it, decoded once, with the configured model it was requested
 * from. Width and height are null when the response named no size.
 */
public record GeneratedImageBytes(byte[] imageData,
                                  String contentType,
                                  Integer width,
                                  Integer height,
                                  String model) {
}
