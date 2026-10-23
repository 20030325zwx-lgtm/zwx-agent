package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineruBlockParserTest {

    @Test
    void parsesTextTableAndFigureBlocksWithPageAndRegion() {
        String contentList = """
                [
                  {"type":"text","text":"2.3.2 待办功能流程测试","text_level":2,"page_idx":11,"bbox":[10,20,300,40]},
                  {"type":"text","text":"本节描述撤回流程的验收点。","page_idx":11,"bbox":[10,45,300,80]},
                  {"type":"table","table_caption":["表 1 撤回流验收"],"table_body":"<table><thead><tr><th>流程</th><th>检查项</th></tr></thead><tbody><tr><td>撤回流</td><td>检查待办是否删除</td></tr></tbody></table>","page_idx":11,"bbox":[10,90,300,200]},
                  {"type":"image","img_path":"images/flow.png","img_caption":["图 1 撤回流程"],"page_idx":12,"bbox":[10,20,300,180]}
                ]
                """;
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("document/content_list.json", contentList.getBytes(StandardCharsets.UTF_8));

        List<MineruBlockParser.MineruBlock> blocks = new MineruBlockParser(new ObjectMapper())
                .parse(new MineruParseResult("# parsed", files));

        assertEquals(4, blocks.size());
        assertTrue(blocks.get(0).heading());
        assertEquals(2, blocks.get(0).headingLevel());
        assertEquals(12, blocks.get(0).pageNumber());
        MineruBlockParser.MineruBlock table = blocks.get(2);
        assertEquals(AgentKnowledgeBlock.TABLE, table.type());
        assertEquals("表 1 撤回流验收", table.caption());
        assertEquals(List.of("流程", "检查项"), table.table().headers());
        assertEquals("撤回流", table.table().rows().get(0).get(0));
        assertEquals(12, table.pageNumber());
        MineruBlockParser.MineruBlock figure = blocks.get(3);
        assertEquals(AgentKnowledgeBlock.FIGURE, figure.type());
        assertEquals("images/flow.png", figure.imagePath());
        assertEquals("图 1 撤回流程", figure.caption());
    }

    @Test
    void toleratesContentListWrappedInPages() {
        String json = """
                {"pages":[{"page_idx":0,"blocks":[{"type":"text","text":"第一页正文"}]},
                          {"page_idx":1,"blocks":[{"type":"text","text":"第二页正文"}]}]}
                """;
        Map<String, byte[]> files = Map.of("result_content_list.json", json.getBytes(StandardCharsets.UTF_8));

        List<MineruBlockParser.MineruBlock> blocks = new MineruBlockParser(new ObjectMapper())
                .parse(new MineruParseResult("# parsed", files));

        assertEquals(2, blocks.size());
        assertEquals(1, blocks.get(0).pageNumber());
        assertEquals(2, blocks.get(1).pageNumber());
    }
}
