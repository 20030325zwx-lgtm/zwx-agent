package com.zwx.zwxagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingDocumentResult(Map<String, Object> content) {
}
