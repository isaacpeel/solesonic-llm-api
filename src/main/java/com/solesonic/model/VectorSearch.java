package com.solesonic.model;

public record VectorSearch(String query,
                           double similarityThreshold,
                           int topK){
}
