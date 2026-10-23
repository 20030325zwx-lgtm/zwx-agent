package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineruDocumentParserTest {
    @Test
    void collectsEveryMineruZipOutputAndSelectsFullMarkdown() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            add(zip, "document/full.md", "# Parsed document");
            add(zip, "document/page_content_list.json", "[{\"type\":\"table\"}]");
            add(zip, "document/layout.json", "{\"layout\":true}");
            add(zip, "document/main.html", "<main>body</main>");
            zip.putNextEntry(new ZipEntry("document/images/page_0_table_0.png"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }

        MineruDocumentParser parser = new MineruDocumentParser("", "https://mineru.net", "vlm", "ch", 30, 500, 1024 * 1024);
        MineruParseResult result = parser.extractResult(buffer.toByteArray());

        assertEquals("# Parsed document", result.markdown());
        assertEquals(5, result.files().size());
        assertEquals(2, result.textFiles(".json").size());
        assertEquals("<main>body</main>", result.textFiles(".html").get("document/main.html"));
        assertArrayEquals(new byte[]{1, 2, 3}, result.files().get("document/images/page_0_table_0.png"));
        assertTrue(result.files().containsKey("document/page_content_list.json"));
    }

    private void add(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
