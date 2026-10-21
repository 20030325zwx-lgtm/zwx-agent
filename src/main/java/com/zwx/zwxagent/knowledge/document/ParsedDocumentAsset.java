package com.zwx.zwxagent.knowledge.document;

/** A non-text document element that can later be described and indexed separately. */
public record ParsedDocumentAsset(String type, String reference, String description) { }
