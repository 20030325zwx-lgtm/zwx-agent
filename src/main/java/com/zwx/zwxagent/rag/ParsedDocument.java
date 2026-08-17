package com.zwx.zwxagent.rag;

import java.util.List;
import java.util.Map;

/** Canonical output of the document parsing module before chunking/indexing. */
public record ParsedDocument(String content, String parser, List<ParsedDocumentAsset> assets,
                             Map<String, Object> metadata) {
    public ParsedDocument {
        assets = assets == null ? List.of() : List.copyOf(assets);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
