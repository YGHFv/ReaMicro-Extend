"""独立云任务 Worker 入口。"""
import asyncio

from app.main import get_state_store, recover_interrupted_tasks, task_scheduler_loop


async def main() -> None:
    get_state_store()
    recover_interrupted_tasks()
    await task_scheduler_loop()


if __name__ == "__main__":
    asyncio.run(main())
