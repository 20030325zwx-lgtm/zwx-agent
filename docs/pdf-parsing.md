# PDF Parsing

Private knowledge documents support Markdown, text, PDF, and (when Tika Server is configured) Office uploads. Parsing is an independent module that runs asynchronously before the existing token splitter and pgvector indexing flow.

## Parsing module

`DocumentParsingModule` is the only entry point for document extraction. It returns canonical text, parser metadata, and non-text asset references; chunking and embedding stay in the knowledge service.

The current priority is:

1. MinerU for PDF when enabled.
2. Optional Apache Tika Server for Office files and general fallback extraction. HTML tables are normalized into Markdown before chunking.
3. iText for PDF text-layer fallback, or UTF-8 decoding for Markdown/text.

Set `APP_TIKA_BASE_URL` to enable the Tika adapter. If it is not set, Office files are rejected during asynchronous indexing instead of being treated as binary text.

## Default

The default parser is iText. It is fast and requires no separate process, but only reads a PDF text layer. Scanned PDFs need OCR and will be marked `FAILED` when no text can be extracted.

## MinerU Enhancement

MinerU is an optional, isolated parser for layout-heavy and scanned PDFs. It produces Markdown, which keeps headings, tables, and reading order available to the existing chunker.

Enable it only on a supported worker that has the official MinerU CLI installed:

```yaml
app:
  pdf:
    mineru:
      enabled: true
      command: mineru
      backend: pipeline
      timeout-seconds: 300
```

The integration invokes the official CLI shape `mineru -p <input> -o <output> -b pipeline`, reads its generated Markdown, then removes the temporary workspace. If MinerU is disabled, unavailable, times out, or returns no Markdown, indexing falls back to iText.

Do not run the MinerU model inside the Spring Boot process. Use a separate Linux worker/service in production; this keeps model downloads, OCR memory use, and parser failures outside the chat API process.
