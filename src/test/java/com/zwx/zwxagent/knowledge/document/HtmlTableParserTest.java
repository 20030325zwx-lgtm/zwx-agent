package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HtmlTableParserTest {

    @Test
    void expandsRowspanAndColspanIntoHeaderLabeledRows() {
        String html = """
                <table>
                  <thead>
                    <tr><th rowspan="2">流程</th><th colspan="2">检查项</th></tr>
                    <tr><th>测试动作</th><th>检查内容</th></tr>
                  </thead>
                  <tbody>
                    <tr><td rowspan="2">撤回流</td><td>未审批待办</td><td>检查待办是否删除</td></tr>
                    <tr><td>已审批待办</td><td>检查是否有撤销提示</td></tr>
                  </tbody>
                </table>
                """;

        HtmlTableParser.TableData data = HtmlTableParser.parse(html, "2.3.2 待办流程");

        assertEquals("2.3.2 待办流程", data.caption());
        assertEquals(List.of("流程", "检查项 / 测试动作", "检查项 / 检查内容"), data.headers());
        assertEquals(2, data.rows().size());
        assertEquals(List.of("撤回流", "未审批待办", "检查待办是否删除"), data.rows().get(0));
        assertEquals("撤回流", data.rows().get(1).get(0));
        assertEquals("[表格记录] 流程：撤回流；检查项 / 测试动作：已审批待办；检查项 / 检查内容：检查是否有撤销提示",
                data.recordText(1));
    }

    @Test
    void parsesMarkdownPipeTable() {
        String markdown = """
                | 流程 | 检查项 | 结果 |
                | --- | --- | --- |
                | 撤回流 | 待办删除 | 通过 |
                | 撤回流 | 状态更新 | 通过 |
                """;

        HtmlTableParser.TableData data = HtmlTableParser.parseMarkdown(markdown, "撤回流验收");

        assertNotNull(data);
        assertEquals(List.of("流程", "检查项", "结果"), data.headers());
        assertEquals(2, data.rows().size());
        assertFalse(data.recordBlock(0, 2).contains("|"));
    }
}
