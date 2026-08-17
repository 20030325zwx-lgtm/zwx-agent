package com.zwx.zwxagent.rag;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Optional Apache Tika Server adapter. An unavailable server never breaks indexing. */
@Component
public class TikaRemoteParser {
    private final RestClient client;

    public TikaRemoteParser(@org.springframework.beans.factory.annotation.Value("${app.document-parser.tika.base-url:}") String baseUrl) {
        this.client = baseUrl == null || baseUrl.isBlank() ? null : RestClient.builder().baseUrl(baseUrl).build();
    }

    public Optional<ParsedDocument> parse(byte[] content, String filename, String contentType) {
        if (client == null) return Optional.empty();
        try {
            String html = client.post().uri("/tika")
                    .contentType(MediaType.parseMediaType(contentType))
                    .accept(MediaType.TEXT_HTML)
                    .body(content)
                    .retrieve().body(String.class);
            if (html == null || html.isBlank()) return Optional.empty();
            org.jsoup.nodes.Document document = Jsoup.parse(html);
            List<ParsedDocumentAsset> assets = new ArrayList<>();
            Elements images = document.select("img");
            for (Element image : images) assets.add(new ParsedDocumentAsset("image", image.attr("src"), ""));
            Elements tableElements = document.select("table");
            String text;
            if (document.body() == null) text = html;
            else {
                var body = document.body().clone();
                body.select("table").remove();
                text = body.text();
            }
            String tables = tableElements.stream().map(this::toMarkdownTable)
                    .filter(value -> !value.isBlank()).reduce("", (left, right) -> left + "\n\n" + right);
            String normalized = (text + tables).trim();
            return normalized.isBlank() ? Optional.empty() : Optional.of(new ParsedDocument(normalized, "tika", assets,
                    java.util.Map.of("filename", filename, "tableCount", tableElements.size())));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String toMarkdownTable(Element table) {
        List<List<String>> rows = table.select("tr").stream().map(row -> row.select("th,td").stream()
                .map(cell -> cell.text().replace("|", "\\|"))
                .toList()).filter(row -> !row.isEmpty()).toList();
        if (rows.isEmpty()) return "";
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder markdown = new StringBuilder();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            markdown.append('|');
            for (int column = 0; column < columns; column++) markdown.append(' ').append(column < row.size() ? row.get(column) : "").append(" |");
            markdown.append('\n');
            if (rowIndex == 0) {
                markdown.append('|');
                for (int column = 0; column < columns; column++) markdown.append(" --- |");
                markdown.append('\n');
            }
        }
        return markdown.toString().trim();
    }
}
