"""content_list 契约单测：不依赖 docling，用鸭子类型假对象直接验证转换规则。

这里锁的是与 Java MineruBlockParser 的字段契约，破坏它会导致
引用定位 / 表格行 / 图纸分组静默失效。
"""
from types import SimpleNamespace

from app.content_list import build_content_list, to_top_left_bbox


class FakeBBox:
    def __init__(self, left, top, right, bottom, origin="CoordOrigin.BOTTOMLEFT"):
        self.l, self.t, self.r, self.b = left, top, right, bottom
        self.coord_origin = origin


class FakeImage:
    def save(self, buffer, format=None):  # noqa: A002 - 对齐 PIL 签名
        buffer.write(b"\x89PNG\r\n\x1a\nfake")
        self.format = format


class FakeRef:
    def __init__(self, text):
        self._text = text

    def resolve(self, doc):  # noqa: ARG002
        return SimpleNamespace(text=self._text)


class FakeItem:
    def __init__(self, label, **attributes):
        self.label = SimpleNamespace(value=label)
        for key, value in attributes.items():
            setattr(self, key, value)


def fake_document(items, height=800):
    return SimpleNamespace(
        pages={1: SimpleNamespace(size=SimpleNamespace(height=height))},
        iterate_items=lambda: iter(items),
    )


def paragraph(text, bbox=None, page_no=1):
    prov = [SimpleNamespace(page_no=page_no, bbox=bbox)] if bbox is not None else []
    return FakeItem("paragraph", text=text, prov=prov)


def test_bbox_converts_bottom_left_to_top_left():
    assert to_top_left_bbox(FakeBBox(10, 700, 110, 750), 800) == [10, 50, 110, 100]


def test_text_and_heading_fields():
    doc = fake_document([
        (paragraph("正文段落", FakeBBox(10, 700, 110, 750)), 0),
        (FakeItem("section_header", text="第三章 审批流程",
                  prov=[SimpleNamespace(page_no=1, bbox=FakeBBox(10, 600, 300, 640))]), 2),
    ])
    items, images = build_content_list(doc)
    assert images == {}
    assert items[0] == {"type": "text", "page_idx": 0, "bbox": [10, 50, 110, 100], "text": "正文段落"}
    assert items[1]["text_level"] == 2
    assert items[1]["bbox"] == [10, 160, 300, 200]


def test_missing_bbox_is_omitted_not_null():
    doc = fake_document([(paragraph("无定位段落"), 0)])
    items, _ = build_content_list(doc)
    assert "bbox" not in items[0]


def test_table_keeps_html_and_captions():
    table = FakeItem(
        "table",
        text="",
        prov=[SimpleNamespace(page_no=1, bbox=FakeBBox(0, 100, 500, 300))],
        captions=[FakeRef("表 1 付款条款")],
        export_to_html=lambda doc=None: "<table><tr><td>付款</td></tr></table>",
    )
    doc = fake_document([(table, 0)])
    items, _ = build_content_list(doc)
    assert items[0]["type"] == "table"
    assert items[0]["table_caption"] == ["表 1 付款条款"]
    assert items[0]["table_footnote"] == []
    assert "<td>付款</td>" in items[0]["table_body"]


def test_picture_exports_png_and_caption():
    picture = FakeItem(
        "picture",
        prov=[SimpleNamespace(page_no=1, bbox=FakeBBox(0, 400, 300, 700))],
        captions=[FakeRef("图 3 审批流程图")],
        get_image=lambda doc=None: FakeImage(),
    )
    doc = fake_document([(picture, 0)])
    items, images = build_content_list(doc)
    assert items[0]["type"] == "image"
    assert items[0]["img_path"] == "images/p1_0000.png"
    assert items[0]["img_caption"] == ["图 3 审批流程图"]
    assert images["images/p1_0000.png"].startswith(b"\x89PNG")


def test_header_footer_and_standalone_caption_are_skipped():
    doc = fake_document([
        (FakeItem("page_header", text="页眉"), 0),
        (FakeItem("caption", text="图 1 说明"), 0),
        (FakeItem("page_footer", text="页脚"), 0),
        (paragraph("正文"), 0),
    ])
    items, _ = build_content_list(doc)
    assert [entry.get("text") for entry in items] == ["正文"]


def test_formula_maps_to_equation():
    doc = fake_document([(FakeItem("formula", text="E=mc^2", prov=[]), 0)])
    items, _ = build_content_list(doc)
    assert items[0]["type"] == "equation"
    assert items[0]["text"] == "E=mc^2"
