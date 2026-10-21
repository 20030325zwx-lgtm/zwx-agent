package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DoclingDocumentResult(Map<String, Object> content) {
}
