"""文本分块。

对应"可先假设/先做"清单第 1 项与对接说明 第 7 节：

- 分块大小 800 字符、重叠 100 字符（均可配置），每块长度不超过 ``chunk_size``；
- 优先在段落、句子边界切分；若在 ``[start + 最小推进量, limit]`` 内找不到自然断点，
  则在长度上限处切分，避免产生大量碎片块；
- ``char_start`` / ``char_end`` 为原文精确区间，满足 ``text[char_start:char_end] == chunk.text``。

契约 第 2 节明确本期不包含 Chunk 参数，因此本模块参数由文档索引服务自主决定，
后续如 Maki 对搜索结果片段有长度要求再调整。
"""

from __future__ import annotations

from bisect import bisect_right

from .models import Chunk

# 句末与段末边界字符
_BREAK_CHARS = "。！？!?；;…\n"


def _breakpoints(text: str) -> list[int]:
    """返回所有自然断点（每个断点为片段结束后的下标，递增）。"""
    points: list[int] = []
    total = len(text)
    for index, char in enumerate(text):
        if char in _BREAK_CHARS:
            points.append(index + 1)
        elif char == "." and (index + 1 >= total or text[index + 1].isspace()):
            points.append(index + 1)
    return points


def chunk_text(text: str, *, chunk_size: int, chunk_overlap: int) -> list[Chunk]:
    """将 ``text`` 切分为带重叠的分块列表。"""
    if chunk_size <= 0:
        raise ValueError("chunk_size 必须为正整数")
    if not 0 <= chunk_overlap < chunk_size:
        raise ValueError("chunk_overlap 必须满足 0 <= overlap < chunk_size")
    if not text or not text.strip():
        return []

    total = len(text)
    points = _breakpoints(text)
    min_advance = chunk_size - chunk_overlap
    chunks: list[Chunk] = []

    start = 0
    while start < total:
        limit = min(start + chunk_size, total)
        end = limit

        position = bisect_right(points, limit) - 1
        if position >= 0:
            candidate = points[position]
            if candidate - start >= min_advance:
                end = candidate

        begin, finish = start, end
        while begin < finish and text[begin].isspace():
            begin += 1
        while finish > begin and text[finish - 1].isspace():
            finish -= 1

        if finish > begin:
            chunks.append(
                Chunk(index=len(chunks), text=text[begin:finish], char_start=begin, char_end=finish)
            )

        if end >= total:
            break
        start = max(end - chunk_overlap, start + 1)

    return chunks
