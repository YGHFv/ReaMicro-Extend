"""独立云任务 Worker 入口。"""
import asyncio

from app.main import (
    get_state_store,
    migrate_device_tasks_to_server,
    recover_interrupted_tasks,
    server_snapshot_loop,
    task_scheduler_loop,
)


async def main() -> None:
    get_state_store()
    recover_interrupted_tasks()
    # 与主进程启动一致：把历史 device 任务迁回服务器，避免它们永远不被调度。
    migrate_device_tasks_to_server()
    await asyncio.gather(task_scheduler_loop(), server_snapshot_loop())


if __name__ == "__main__":
    asyncio.run(main())
