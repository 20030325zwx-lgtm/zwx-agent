"""真实 PDF 冒烟测试：默认跳过，需本地装好 docling 并指定 PARSE_SMOKE_PDF。"""
import io
import json
import os
import zipfile

import pytest


@pytest.mark.skipif(
    not os.getenv("PARSE_SMOKE_PDF") or not os.path.exists(os.getenv("PARSE_SMOKE_PDF", "")),
    reason="设置 PARSE_SMOKE_PDF 指向真实 PDF 后才运行",
)
def test_parse_real_pdf_returns_mineru_compatible_zip():
    from app.converter import convert_to_zip

    path = os.environ["PARSE_SMOKE_PDF"]
    with open(path, "rb") as handle:
        data = handle.read()
    payload = convert_to_zip(data, os.path.basename(path))
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        names = archive.namelist()
        assert "full.md" in names
        assert "content_list.json" in names
        items = json.loads(archive.read("content_list.json"))
        assert isinstance(items, list)
        for item in items:
            assert item["type"] in {"text", "table", "image", "equation"}
            assert isinstance(item.get("page_idx"), int)
