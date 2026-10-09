from __future__ import annotations

import pytest

from index_service.chunker import chunk_text


def test_offsets_map_back_to_original_text() -> None:
    text = "第一段内容。" * 200
    chunks = chunk_text(text, chunk_size=200, chunk_overlap=20)

    assert len(chunks) > 5
    for chunk in chunks:
        assert text[chunk.char_start:chunk.char_end] == chunk.text
        assert chunk.text == chunk.text.strip()
        assert len(chunk.text) <= 200
    assert [chunk.index for chunk in chunks] == list(range(len(chunks)))


def test_neighbouring_chunks_overlap() -> None:
    text = "句子内容大约十个字。" * 40
    chunks = chunk_text(text, chunk_size=200, chunk_overlap=40)

    assert len(chunks) > 2
    for previous, current in zip(chunks, chunks[1:]):
        assert current.char_start < previous.char_end


def test_zero_overlap_produces_disjoint_chunks() -> None:
    text = "内容。" * 200
    chunks = chunk_text(text, chunk_size=100, chunk_overlap=0)

    assert len(chunks) > 2
    for previous, current in zip(chunks, chunks[1:]):
        assert current.char_start >= previous.char_end


def test_unbroken_text_is_split_without_tiny_fragments() -> None:
    text = "a" * 1000
    chunks = chunk_text(text, chunk_size=100, chunk_overlap=10)

    assert chunks[0].char_start == 0
    assert chunks[-1].char_end == 1000
    for chunk in chunks:
        assert 90 <= len(chunk.text) <= 100


def test_short_leading_sentence_is_not_left_alone() -> None:
    text = "标题。\n" + "正文内容。" * 200
    chunks = chunk_text(text, chunk_size=400, chunk_overlap=50)

    assert len(chunks) > 1
    for chunk in chunks[:-1]:
        assert len(chunk.text) >= 350


def test_blank_text_returns_no_chunks() -> None:
    assert chunk_text("   \n\t ", chunk_size=100, chunk_overlap=10) == []


@pytest.mark.parametrize(
    ("chunk_size", "chunk_overlap"),
    [(0, 0), (-1, 0), (100, 100), (100, -1), (100, 200)],
)
def test_invalid_parameters_raise(chunk_size: int, chunk_overlap: int) -> None:
    with pytest.raises(ValueError):
        chunk_text("内容", chunk_size=chunk_size, chunk_overlap=chunk_overlap)
