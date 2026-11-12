"""服务配置：全部来自环境变量，默认值面向本地开发。"""
import os


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None or value == "":
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


HOST = os.getenv("PARSE_HOST", "0.0.0.0")
PORT = int(os.getenv("PARSE_PORT", "8131"))

# 单个输入文件上限（默认 50MB）
MAX_BYTES = int(os.getenv("PARSE_MAX_BYTES", str(50 * 1024 * 1024)))
# 支持的扩展名（与 Java 侧上传入口保持一致）
SUPPORTED_EXTENSIONS = {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"}

# Docling 管线参数
DO_OCR = _env_bool("PARSE_DO_OCR", True)
DO_TABLE_STRUCTURE = _env_bool("PARSE_DO_TABLE_STRUCTURE", True)
IMAGES_SCALE = float(os.getenv("PARSE_IMAGES_SCALE", "2.0"))
# 模型缓存目录（Docker 内为 /root/.cache/huggingface）
HF_HOME = os.getenv("HF_HOME", "/root/.cache/huggingface")
