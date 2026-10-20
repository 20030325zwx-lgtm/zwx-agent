package com.zwx.zwxagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingChunkResponse(List<DoclingChunk> chunks,
                                   @JsonProperty("processing_time") Double processingTime) {
}
