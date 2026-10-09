"""CendoDrive 文档索引服务。

职责：接收 Lucky 投递的索引事件，读取 PDF/DOCX/TXT/Markdown，完成文本提取与分块。
Embedding 与 Milvus 写入待 Maki 提供模型与集合结构后接入（见 README 的假设清单）。
"""

__all__ = ["__version__"]

__version__ = "0.1.0"
