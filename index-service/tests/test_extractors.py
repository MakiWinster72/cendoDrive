from __future__ import annotations

from pathlib import Path
from typing import Callable

import pytest

from index_service.config import Settings
from index_service.extractors import (
    CorruptDocument,
    EmptyText,
    TooLargeFile,
    TooManyPages,
    UnsupportedFormat,
    decode_text,
    extension_of,
    extract,
)

from helpers import write_docx, write_pdf


def test_extension_detection() -> None:
    assert extension_of("a.PDF") == "pdf"
    assert extension_of("a.MarkDown") == "markdown"
    assert extension_of("no-extension") == ""


def test_decode_text_prefers_utf8_then_gb18030() -> None:
    assert decode_text("中文".encode("utf-8")) == ("中文", "utf-8-sig")
    assert decode_text("中文".encode("gb18030")) == ("中文", "gb18030")
    assert decode_text(b"\xff\xfe\xfa")[1] == "utf-8/replace"


def test_extract_txt_utf8(files_root: Path, settings: Settings) -> None:
    path = files_root / "notes.txt"
    path.write_text("第一行\n第二行", encoding="utf-8")

    result = extract(path, "notes.txt", settings)

    assert result.text == "第一行\n第二行"
    assert result.encoding == "utf-8-sig"
    assert result.pages is None


def test_extract_txt_falls_back_to_gb18030(files_root: Path, settings: Settings) -> None:
    path = files_root / "legacy.txt"
    path.write_bytes("中文GBK内容".encode("gb18030"))

    result = extract(path, "legacy.txt", settings)

    assert result.text == "中文GBK内容"
    assert result.encoding == "gb18030"


def test_extract_markdown(files_root: Path, settings: Settings) -> None:
    path = files_root / "readme.md"
    path.write_text("# 标题\n\n- 条目", encoding="utf-8")

    result = extract(path, "readme.md", settings)

    assert "# 标题" in result.text


def test_extract_docx_includes_paragraphs_and_tables(
    files_root: Path, settings: Settings
) -> None:
    path = write_docx(files_root / "report.docx", ["第一段", "第二段"], [["A", "B"]])

    result = extract(path, "report.docx", settings)

    assert "第一段" in result.text
    assert "A\tB" in result.text


def test_extract_pdf_reports_page_count(files_root: Path, settings: Settings) -> None:
    path = write_pdf(files_root / "paper.pdf", ["Hello PDF", "Second page"])

    result = extract(path, "paper.pdf", settings)

    assert "Hello PDF" in result.text
    assert "Second page" in result.text
    assert result.pages == 2


def test_unsupported_extension_is_rejected(files_root: Path, settings: Settings) -> None:
    path = files_root / "archive.zip"
    path.write_bytes(b"PK\x03\x04")

    with pytest.raises(UnsupportedFormat):
        extract(path, "archive.zip", settings)


def test_oversized_file_is_rejected(
    files_root: Path, make_settings: Callable[..., Settings]
) -> None:
    settings = make_settings(max_file_bytes=4)
    path = files_root / "big.txt"
    path.write_text("0123456789", encoding="utf-8")

    with pytest.raises(TooLargeFile):
        extract(path, "big.txt", settings)


def test_too_many_pages_is_rejected(
    files_root: Path, make_settings: Callable[..., Settings]
) -> None:
    settings = make_settings(max_pdf_pages=1)
    path = write_pdf(files_root / "two-pages.pdf", ["page one", "page two"])

    with pytest.raises(TooManyPages):
        extract(path, "two-pages.pdf", settings)


def test_empty_text_is_reported(files_root: Path, settings: Settings) -> None:
    path = files_root / "blank.txt"
    path.write_text("   \n  ", encoding="utf-8")

    with pytest.raises(EmptyText):
        extract(path, "blank.txt", settings)


def test_pdf_without_text_layer_is_reported(files_root: Path, settings: Settings) -> None:
    path = write_pdf(files_root / "scanned.pdf", [""])

    with pytest.raises(EmptyText):
        extract(path, "scanned.pdf", settings)


def test_corrupt_pdf_is_reported(files_root: Path, settings: Settings) -> None:
    path = files_root / "broken.pdf"
    path.write_bytes(b"%PDF-1.4 not really a pdf")

    with pytest.raises(CorruptDocument):
        extract(path, "broken.pdf", settings)
