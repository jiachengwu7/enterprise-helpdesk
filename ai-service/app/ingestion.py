from io import BytesIO
import zipfile
from pypdf import PdfReader
from docx import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter


def can_publish(current, job_version):
    return bool(current and not current["deleted"] and current["version"] == job_version)


def parse_document(filename, content):
    suffix = filename.rsplit(".", 1)[-1].lower()
    if suffix in ("txt", "md"):
        text = content.decode("utf-8-sig")
        if "\x00" in text:
            raise ValueError("文本编码无效")
    elif suffix == "pdf":
        reader = PdfReader(BytesIO(content))
        if reader.is_encrypted or len(reader.pages) > 500:
            raise ValueError("不支持加密或超长PDF")
        text = "\n\n".join(page.extract_text() or "" for page in reader.pages)
    elif suffix == "docx":
        with zipfile.ZipFile(BytesIO(content)) as z:
            if sum(x.file_size for x in z.infolist()) > 50 * 1024 * 1024:
                raise ValueError("文档解压后过大")
        doc = Document(BytesIO(content))
        text = "\n".join(
            [p.text for p in doc.paragraphs]
            + [" | ".join(c.text for c in row.cells) for t in doc.tables for row in t.rows]
        )
    else:
        raise ValueError("不支持的格式")
    if not text.strip() or len(text) > 2_000_000:
        raise ValueError("无可提取文本或文本过大")
    return text.strip()


def split_document(text):
    return RecursiveCharacterTextSplitter(
        chunk_size=900, chunk_overlap=140, separators=["\n\n", "\n", "。", "；", " ", ""]
    ).split_text(text)
