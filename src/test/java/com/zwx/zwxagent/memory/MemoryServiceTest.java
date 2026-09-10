package com.zwx.zwxagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {

    @Test
    void parsesPlainJsonArray() {
        assertEquals(List.of("喜欢日料", "在上海工作"), MemoryService.parseFactsJson("[\"喜欢日料\", \"在上海工作\"]"));
    }

    @Test
    void parsesFencedJsonAndObjectItems() {
        String raw = "```json\n[{\"fact\": \"养了一只猫\"}, \"不吃香菜\"]\n```";
        assertEquals(List.of("养了一只猫", "不吃香菜"), MemoryService.parseFactsJson(raw));
    }

    @Test
    void junkOutputYieldsEmptyList() {
        assertTrue(MemoryService.parseFactsJson("抱歉我无法提取").isEmpty());
        assertTrue(MemoryService.parseFactsJson(null).isEmpty());
        assertTrue(MemoryService.parseFactsJson("{broken json").isEmpty());
    }

    @Test
    void capsAtThreeFactsAndDeduplicates() {
        String raw = "[\"a\", \"a \", \"b\", \"c\", \"d\"]";
        assertEquals(List.of("a", "b", "c"), MemoryService.parseFactsJson(raw));
    }

    @Test
    void normalizeCollapsesWhitespaceAndCapsLength() {
        assertEquals("喜欢 日料", MemoryService.normalizeFact(" 喜欢  \n 日料 "));
        assertEquals(500, MemoryService.normalizeFact("x".repeat(800)).length());
    }

    @Test
    void sha256IsStableAndHex() {
        String hash = MemoryService.sha256Hex("abc");
        assertEquals(64, hash.length());
        assertEquals(hash, MemoryService.sha256Hex("abc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash);
    }

    @Test
    void keywordHitsBeatRecency() {
        List<String> recent = List.of("最近的新事实", "用户喜欢日料", "用户在上海工作");
        List<String> ranked = MemoryService.rankFacts(recent, "推荐一家日料店", 2);
        assertEquals("用户喜欢日料", ranked.getFirst(), "关键词命中的事实应排到最前");
        assertEquals(2, ranked.size());
    }

    @Test
    void recencyOrderBreaksTies() {
        List<String> ranked = MemoryService.rankFacts(List.of("新事实", "旧事实"), "毫不相关的问题", 5);
        assertEquals(List.of("新事实", "旧事实"), ranked);
    }

    @Test
    void extractKeywordsCoversCjkAndLatin() {
        Set<String> keywords = MemoryService.extractKeywords("推荐日料 restaurant 吧");
        assertTrue(keywords.contains("日料"));
        assertTrue(keywords.contains("推荐"));
        assertTrue(keywords.contains("restaurant"));
    }

    @Test
    void extractKeywordsHandlesNullOrEmpty() {
        assertTrue(MemoryService.extractKeywords(null).isEmpty());
        assertTrue(MemoryService.extractKeywords("  ").isEmpty());
    }
}
