"""Docling 引擎封装：输入任意字节 -> 统一结果包（zip）。

docling 采用惰性导入：服务可在未安装模型依赖的环境里启动并暴露 /health，
首次 /parse 才加载模型（模型权重下载到 HF_HOME，Docker 内由命名卷缓存）。
"""
from __future__ import annotations

import io
import json
import os
import tempfile
import zipfile
from functools import lru_cache

from . import settings
from .content_list import build_content_list

SUPPORTED_ENGINES = {"docling", "auto"}


@lru_cache(maxsize=1)
def _converter():
    # 惰性导入：避免 /health 依赖文档解析模型
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.pipeline_options import PdfPipelineOptions
    from docling.document_converter import DocumentConverter, PdfFormatOption

    pipeline = PdfPipelineOptions()
    pipeline.do_ocr = settings.DO_OCR
    pipeline.do_table_structure = settings.DO_TABLE_STRUCTURE
    pipeline.generate_picture_images = True
    pipeline.images_scale = settings.IMAGES_SCALE
    return DocumentConverter(
        format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline)}
    )


def convert_to_zip(data: bytes, filename: str, engine: str = "docling") -> bytes:
    """把文件字节转成 MinerU 兼容 zip：full.md + content_list.json + images/*。"""
    extension = os.path.splitext(filename or "")[1].lower()
    if extension not in settings.SUPPORTED_EXTENSIONS:
        raise ValueError(f"unsupported file type: {extension or '(none)'}")
    if len(data) > settings.MAX_BYTES:
        raise ValueError("file too large")
    engine = (engine or "docling").strip().lower()
    if engine not in SUPPORTED_ENGINES:
        raise ValueError(f"unsupported engine: {engine}")

    with tempfile.TemporaryDirectory(prefix="aster-parse-") as workspace:
        source = os.path.join(workspace, "input" + extension)
        with open(source, "wb") as handle:
            handle.write(data)
        result = _converter().convert(source)
        document = getattr(result, "document", None)
        if document is None:
            raise ValueError("parser returned no document")
        markdown = document.export_to_markdown() or ""
        items, images = build_content_list(document)
    if not markdown.strip() and not items:
        raise ValueError("no extractable content")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("full.md", markdown)
        archive.writestr("content_list.json", json.dumps(items, ensure_ascii=False))
        for name, payload in images.items():
            archive.writestr(name, payload)
    return buffer.getvalue()
