"""启动入口：``python -m index_service``。"""

from __future__ import annotations

import logging
import os


def main() -> None:
    import uvicorn

    from .api import create_app
    from .config import get_settings

    logging.basicConfig(
        level=os.environ.get("INDEX_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    settings = get_settings()
    uvicorn.run(
        create_app(settings=settings),
        host=os.environ.get("INDEX_HOST", "0.0.0.0"),
        port=int(os.environ.get("INDEX_PORT", "8000")),
    )


if __name__ == "__main__":
    main()
