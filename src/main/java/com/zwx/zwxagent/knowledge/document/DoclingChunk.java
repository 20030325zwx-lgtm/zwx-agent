package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingChunk(@JsonProperty("chunk_index") Integer chunkIndex,
                           String text,
                           @JsonProperty("raw_text") String rawText,
                           @JsonProperty("num_tokens") Integer numTokens,
                           List<String> headings,
                           List<String> captions,
                           @JsonProperty("doc_items") List<String> docItems,
                           @JsonProperty("page_numbers") List<Integer> pageNumbers,
                           Map<String, Object> metadata) {
}
