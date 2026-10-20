package com.zwx.zwxagent.rag;

import java.util.List;

public record PreprocessedDocument(String documentType, List<String> furnitureText) {
    public static final String GENERAL = "general";
    public static final String RESUME = "resume";

    public PreprocessedDocument {
        furnitureText = furnitureText == null ? List.of() : List.copyOf(furnitureText);
    }

    public boolean resume() {
        return RESUME.equals(documentType);
    }
}
