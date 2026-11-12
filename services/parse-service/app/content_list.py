"""DoclingDocument -> MinerU 兼容 content_list.json。

设计约束：
- 本模块只做鸭子类型转换，**不 import docling**，因此可以脱离模型依赖单独单测。
- 输出字段与 Java 侧 MineruBlockParser 对齐：
  text / table / image / equation，page_idx 为 0-based，bbox 为左上原点 [x0, y0, x1, y1]。
- bbox 缺失时**省略该字段**（Java 侧对显式 null 会序列化成字符串 "null"，破坏 jsonb 写入）。
"""
from __future__ import annotations

import io

# Docling 的页眉/页脚/页码与独立 caption 不进入正文：
# - header/footer/page_number 在 MinerU 侧同样丢弃；
# - caption 已通过 table/picture 的 captions 引用带回，独立输出会重复。
_SKIP_LABELS = {"page_header", "page_footer", "page_number", "caption"}
_HEADING_LABELS = {"section_header", "title"}
_FIGURE_LABELS = {"picture", "figure"}
_FORMULA_LABELS = {"formula", "equation"}


def _label(item) -> str:
    label = getattr(item, "label", None)
    if label is None:
        return ""
    value = getattr(label, "value", None)
    return str(value if value is not None else label).lower()


def _item_text(item) -> str:
    text = getattr(item, "text", None)
    return "" if text is None else str(text)


def _page_height(doc, page_no) -> float | None:
    try:
        pages = getattr(doc, "pages", None) or {}
        page = pages.get(page_no) if hasattr(pages, "get") else None
        if page is None and hasattr(pages, "get"):
            page = pages.get(str(page_no))
        size = getattr(page, "size", None)
        height = getattr(size, "height", None)
        return float(height) if height is not None else None
    except Exception:
        return None


def to_top_left_bbox(bbox, page_height: float | None):
    """把 Docling bbox（默认左下原点）转成左上原点；已是左上原点则原样保留数值。"""
    left = float(getattr(bbox, "l"))
    top = float(getattr(bbox, "t"))
    right = float(getattr(bbox, "r"))
    bottom = float(getattr(bbox, "b"))
    origin = str(getattr(bbox, "coord_origin", "")).upper()
    if "BOTTOM" in origin and page_height is not None:
        top, bottom = page_height - bottom, page_height - top
    return [round(left, 2), round(top, 2), round(right, 2), round(bottom, 2)]


def _page_and_bbox(item, doc):
    """返回 (page_idx, bbox 或 None)。page_idx 统一 0-based。"""
    prov = getattr(item, "prov", None) or []
    if not prov:
        return 0, None
    first = prov[0]
    page_no = getattr(first, "page_no", None)
    page_idx = max(int(page_no) - 1, 0) if page_no is not None else 0
    bbox = getattr(first, "bbox", None)
    if bbox is None:
        return page_idx, None
    try:
        return page_idx, to_top_left_bbox(bbox, _page_height(doc, page_no))
    except Exception:
        return page_idx, None


def _captions(item, doc):
    captions = []
    for ref in getattr(item, "captions", None) or []:
        try:
            target = ref.resolve(doc) if hasattr(ref, "resolve") else ref
            text = getattr(target, "text", None)
            if text is not None and str(text).strip():
                captions.append(str(text).strip())
        except Exception:
            continue
    return captions


def _table_html(item, doc) -> str:
    try:
        return item.export_to_html(doc=doc)
    except TypeError:
        try:
            return item.export_to_html()
        except Exception:
            return ""
    except Exception:
        return ""


def _picture_image(item, doc):
    try:
        return item.get_image(doc)
    except Exception:
        return None


def _png_bytes(image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def build_content_list(doc, image_prefix: str = "images"):
    """返回 (content_list 数组, {相对路径: png 字节})。"""
    items = []
    images: dict[str, bytes] = {}
    sequence = 0

    for item, level in doc.iterate_items():
        label = _label(item)
        if label in _SKIP_LABELS:
            continue
        page_idx, bbox = _page_and_bbox(item, doc)

        def base(item_type: str) -> dict:
            entry = {"type": item_type, "page_idx": page_idx}
            if bbox is not None:
                entry["bbox"] = bbox
            return entry

        if label in _HEADING_LABELS:
            text = _item_text(item).strip()
            if not text:
                continue
            entry = base("text")
            entry["text"] = text
            entry["text_level"] = max(int(level or 0), 1)
            items.append(entry)
            continue

        if label == "table":
            html = _table_html(item, doc)
            text = _item_text(item)
            if not html.strip() and not text.strip():
                continue
            entry = base("table")
            entry["table_body"] = html
            if text.strip() and not html.strip():
                entry["text"] = text
            entry["table_caption"] = _captions(item, doc)
            entry["table_footnote"] = []
            items.append(entry)
            continue

        if label in _FIGURE_LABELS:
            image_path = None
            image = _picture_image(item, doc)
            if image is not None:
                page_no = (page_idx + 1) if page_idx is not None else 0
                image_path = f"{image_prefix}/p{page_no}_{sequence:04d}.png"
                images[image_path] = _png_bytes(image)
                sequence += 1
            entry = base("image")
            entry["img_path"] = image_path or ""
            entry["img_caption"] = _captions(item, doc)
            entry["image_footnote"] = []
            items.append(entry)
            continue

        if label in _FORMULA_LABELS:
            text = _item_text(item).strip()
            if not text:
                continue
            entry = base("equation")
            entry["text"] = text
            items.append(entry)
            continue

        text = _item_text(item).strip()
        if not text:
            continue
        entry = base("text")
        entry["text"] = text
        items.append(entry)

    return items, images
