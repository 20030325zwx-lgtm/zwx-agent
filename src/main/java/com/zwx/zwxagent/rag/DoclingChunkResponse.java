package com.zwx.zwxagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingChunkResponse(List<DoclingChunk> chunks,
                                   @JsonProperty("processing_time") Double processingTime,
                                   List<DoclingDocumentResult> documents) {

    public DoclingChunkResponse(List<DoclingChunk> chunks, Double processingTime) {
        this(chunks, processingTime, List.of());
    }

    public DoclingChunkResponse {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
