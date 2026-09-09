package com.zwx.zwxagent.skills;

public record SkillCatalogItem(String id, String name, String description, String trigger, boolean enabled, String source) {

    public SkillCatalogItem(String id, String name, String description, String trigger, boolean enabled) {
        this(id, name, description, trigger, enabled, "builtin");
    }
}
