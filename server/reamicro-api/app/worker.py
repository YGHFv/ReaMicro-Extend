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

    migrate_device_tasks_to_server()
    await asyncio.gather(task_scheduler_loop(), server_snapshot_loop())


if __name__ == "__main__":
    asyncio.run(main())
