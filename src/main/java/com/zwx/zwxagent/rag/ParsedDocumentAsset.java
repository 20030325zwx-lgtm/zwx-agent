package com.zwx.zwxagent.rag;

/** A non-text document element that can later be described and indexed separately. */
public record ParsedDocumentAsset(String type, String reference, String description) { }
