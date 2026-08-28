"""健康检查、能力发现、诊断与指标。

/v1/discovery 是公开的，只暴露认证模式和功能列表，不泄露白名单内容与密钥。
"""

from typing import Any

from fastapi import APIRouter, Depends, Form, Header, HTTPException, Query, Request, UploadFile, File, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasicCredentials

from app import runtime
import asyncio
import json
import secrets
from app.audit import audit_event
from app.config_store import (
    load_config,
    public_server_capabilities,
)
from app.responses import response
from app.scheduler import (
    recover_interrupted_tasks,
    release_sync_loop,
    server_snapshot_loop,
    task_scheduler_loop,
)
from app.security import (
    allow_rate_limit,
    authenticated,
)
from app.state import (
    canonicalize_owner_identities,
    load_credentials,
    load_tasks,
)

router = APIRouter()






@router.get("/v1/health")
async def health() -> dict[str, Any]:
    """公开健康检查；认证状态和详细依赖信息分别由 /v1/meta 与 /health/dependencies 提供。"""
    return response({"status": "ok", "serverId": load_config()["serverId"]})


@router.get("/health/live")
async def health_live() -> dict[str, Any]:
    return response({"status": "alive"})


@router.get("/health/ready")
async def health_ready() -> JSONResponse:
    database = runtime.get_state_store().integrity_check()
    ready = database == "ok" and runtime.CONFIG_ROOT.parent.exists()
    payload = response({"status": "ready" if ready else "not_ready", "database": database})
    return JSONResponse(status_code=200 if ready else 503, content=payload)


@router.get("/health/dependencies")
async def health_dependencies(_: None = Depends(authenticated)) -> dict[str, Any]:
    sync_status = {}
    if runtime.RELEASE_STATUS_PATH.is_file():
        try: sync_status = json.loads(runtime.RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError): sync_status = {"status": "invalid"}
    return response({
        "database": runtime.get_state_store().integrity_check(),
        "releaseCache": (runtime.RELEASE_ROOT / "latest.json").is_file(),
        "releaseSync": sync_status,
        "packageStorage": runtime.PACKAGE_ROOT.exists(),
        "backupStorage": runtime.BACKUP_ROOT.exists(),
    })


@router.get("/v1/diagnostics")
async def diagnostics(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    database = runtime.get_state_store().integrity_check()
    package_count = sum(1 for kind in runtime.PACKAGE_KINDS for _ in (runtime.PACKAGE_ROOT / kind).glob("*/manifest.json"))
    snapshot_count = len(list(runtime.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"))) if runtime.SERVER_BACKUP_ROOT.exists() else 0
    release_status = {}
    if runtime.RELEASE_STATUS_PATH.is_file():
        try: release_status = json.loads(runtime.RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError): release_status = {"status": "invalid"}
    return response({
        "serverId": config.get("serverId", ""),
        "database": database,
        "packages": package_count,
        "credentials": len(load_credentials()),
        "tasks": len(load_tasks()),
        "snapshots": snapshot_count,
        "releaseSync": release_status,
        "storage": {"config": runtime.CONFIG_ROOT.exists(), "packages": runtime.PACKAGE_ROOT.exists(), "backups": runtime.BACKUP_ROOT.exists()},
    })


@router.get("/metrics")
async def metrics(_: None = Depends(authenticated)) -> Response:
    with runtime.metrics_lock:
        counters = dict(runtime.metrics_counters)
        routes = dict(runtime.metrics_routes)
    lines = [
        "# HELP reamicro_http_requests_total Total HTTP responses",
        "# TYPE reamicro_http_requests_total counter",
        f"reamicro_http_requests_total {counters['requests']}",
        "# HELP reamicro_http_errors_total Total unhandled HTTP errors",
        "# TYPE reamicro_http_errors_total counter",
        f"reamicro_http_errors_total {counters['errors']}",
        "# HELP reamicro_http_rate_limited_total Total rate-limited requests",
        "# TYPE reamicro_http_rate_limited_total counter",
        f"reamicro_http_rate_limited_total {counters['rate_limited']}",
        "# HELP reamicro_credentials Current credential count",
        "# TYPE reamicro_credentials gauge",
        f"reamicro_credentials {len(load_credentials())}",
        "# HELP reamicro_tasks Current task count",
        "# TYPE reamicro_tasks gauge",
        f"reamicro_tasks {len(load_tasks())}",
        "# HELP reamicro_tasks_failed Current failed task count",
        "# TYPE reamicro_tasks_failed gauge",
        f"reamicro_tasks_failed {sum(1 for task in load_tasks().values() if task.get('status') == 'failed')}",
        "# HELP reamicro_packages Current package count",
        "# TYPE reamicro_packages gauge",
        f"reamicro_packages {sum(1 for kind in runtime.PACKAGE_KINDS for _ in (runtime.PACKAGE_ROOT / kind).glob('*/manifest.json'))}",
    ]
    for route, count in sorted(routes.items()):
        safe_route = route.replace('"', '\\"')
        lines.append(f'reamicro_http_route_requests_total{{route="{safe_route}"}} {count}')
    return Response("\n".join(lines) + "\n", media_type="text/plain; version=0.0.4")


@router.get("/v1/discovery")
async def discovery() -> dict[str, Any]:
    """公开能力发现，不返回 API Key、账号或白名单内容。"""
    return response(public_server_capabilities(load_config()))


@router.get("/v1/meta")
async def meta(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    return response({**public_server_capabilities(config), "storage": {"state": "sqlite-wal", "multiHost": False}, "healthEndpoints": {"live": "/health/live", "ready": "/health/ready", "dependencies": "/health/dependencies"}})
