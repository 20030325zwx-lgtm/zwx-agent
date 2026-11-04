package com.zwx.zwxagent.chat.memory;

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

    // ---- A0 用户隔离回归：注入必须归属到人，不可归属事实不落库 ----

    @Test
    void factsForWithoutUserNeverQueries() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new MemoryService(jdbc, null, false);
        assertTrue(service.factsFor("tenant", null, "super", "问句", 5).isEmpty(), "缺 userId 不得注入");
        assertTrue(service.factsFor("tenant", "  ", "super", "问句", 5).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void factsForFiltersByExactUser() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new MemoryService(jdbc, null, false);
        org.mockito.Mockito.when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),
                org.mockito.ArgumentMatchers.eq("t1"), org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("super")))
                .thenReturn(java.util.List.of("用户一的事实"));
        assertEquals(java.util.List.of("用户一的事实"), service.factsFor("t1", "u1", "super", "问句", 5));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).query(sql.capture(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(),
                org.mockito.ArgumentMatchers.eq("t1"), org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("super"));
        assertTrue(sql.getValue().contains("user_id = ?"), "注入查询必须按 user_id 过滤");
    }

    @Test
    void upsertSkipsFactsWithoutOwner() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new MemoryService(jdbc, null, false);
        service.upsertFact("t1", "super", null, "conv-1", "无主事实");
        service.upsertFact("t1", "super", " ", "conv-1", "无主事实");
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void upsertDedupesWithinOwnerScope() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new MemoryService(jdbc, null, false);
        service.upsertFact("t1", "super", "u1", "conv-1", "用户一的事实");
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).update(sql.capture(), org.mockito.ArgumentMatchers.any(Object[].class));
        assertTrue(sql.getValue().contains("(tenant_id, user_id, agent_key, fact_hash)"),
                "去重冲突目标必须包含 user_id");
        assertTrue(sql.getValue().contains("WHERE user_id IS NOT NULL"), "冲突目标必须限定可归属记录");
    }
}
