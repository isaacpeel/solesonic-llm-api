package com.solesonic.izzybot.model;

public record VectorSearch(String query,
                           double similarityThreshold,
                           int topK){
}
