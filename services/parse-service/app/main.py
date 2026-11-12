"""Aster 统一解析服务入口。

契约（与 Java 侧 PythonParseClient / MineruDocumentParser 对齐）：
- GET  /health  -> 200 {"status": "ok"}
- POST /parse   -> application/zip，内含 full.md + content_list.json + images/*
"""
from __future__ import annotations

import asyncio
import os

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from . import settings
from .converter import convert_to_zip

app = FastAPI(title="Aster Parse Service", version="0.1.0")

# 解析是重内存操作：默认同时最多 2 个文档，避免 OOM
_MAX_CONCURRENT = int(os.getenv("PARSE_MAX_CONCURRENT", "2"))
_SEMAPHORE = asyncio.Semaphore(_MAX_CONCURRENT)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/parse")
async def parse(file: UploadFile = File(...), engine: str = Form("docling")):
    data = await file.read()
    if len(data) > settings.MAX_BYTES:
        return JSONResponse(status_code=413, content={"error": "file too large"})
    try:
        async with _SEMAPHORE:
            zip_bytes = await run_in_threadpool(
                convert_to_zip, data, file.filename or "input", engine
            )
    except ValueError as exc:
        return JSONResponse(status_code=422, content={"error": str(exc)})
    except Exception as exc:  # 解析器内部异常：暴露类型但不吞掉
        return JSONResponse(
            status_code=500, content={"error": f"{type(exc).__name__}: {exc}"}
        )
    return Response(content=zip_bytes, media_type="application/zip")
