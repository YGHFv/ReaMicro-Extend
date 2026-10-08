import asyncio
import secrets

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response

from app import runtime
from app.admin.layout import admin_error_page
from app.api import admin_routes
from app.api import backups as backups_routes
from app.api import credentials as credentials_routes
from app.api import meta as meta_routes
from app.api import packages as packages_routes
from app.api import releases as releases_routes
from app.api import tasks as tasks_routes
from app.audit import audit_event
from app.responses import response
from app.scheduler import (
    migrate_device_tasks_to_server,
    recover_interrupted_tasks,
    release_sync_loop,
    server_snapshot_loop,
    task_scheduler_loop,
)
from app.security import allow_rate_limit
from app.state import canonicalize_owner_identities

app = FastAPI(title="ReaMicro API", version=runtime.API_VERSION)


for _module in (
    meta_routes,
    packages_routes,
    tasks_routes,
    credentials_routes,
    backups_routes,
    releases_routes,
    admin_routes,
):
    app.include_router(_module.router)


def wants_admin_html(request: Request) -> bool:

    path = request.url.path
    if path != "/admin" and not path.startswith("/admin/"):
        return False
    return "text/html" in request.headers.get("Accept", "").lower()


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> Response:
    request_id = getattr(request.state, "request_id", "")
    if exc.status_code in (302, 303, 307, 308) and exc.headers and exc.headers.get("Location"):
        result = RedirectResponse(exc.headers["Location"], status_code=exc.status_code)
        if request_id:
            result.headers["X-Request-Id"] = request_id
        return result
    detail = exc.detail if isinstance(exc.detail, dict) else response(code="HTTP_ERROR", message=str(exc.detail), request_id=request_id)
    if isinstance(detail, dict):
        detail["requestId"] = request_id or detail.get("requestId", "")
    if wants_admin_html(request):

        message = detail.get("message", "") if isinstance(detail, dict) else str(exc.detail)
        return HTMLResponse(
            admin_error_page(exc.status_code, message, request_id),
            status_code=exc.status_code,
            headers={**(exc.headers or {}), "X-Request-Id": request_id, "Cache-Control": "no-store"},
        )
    return JSONResponse(status_code=exc.status_code, content=detail, headers={**(exc.headers or {}), "X-Request-Id": request_id})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> Response:
    request_id = getattr(request.state, "request_id", "") or "req_" + secrets.token_hex(8)
    client_host = request.client.host if request.client else "unknown"
    with runtime.metrics_lock:
        runtime.metrics_counters["errors"] += 1
    audit_event(
        "unhandled_exception",
        actor=client_host,
        request_id=request_id,
        success=False,
        metadata={"path": request.url.path, "type": type(exc).__name__},
    )
    if wants_admin_html(request):
        return HTMLResponse(
            admin_error_page(500, "服务器内部错误，请凭请求 ID 在审计日志中查询。", request_id),
            status_code=500,
            headers={"X-Request-Id": request_id, "Cache-Control": "no-store"},
        )
    return JSONResponse(
        status_code=500,
        content=response(code="INTERNAL_ERROR", message="服务器内部错误，请使用 requestId 查询日志", request_id=request_id),
        headers={"X-Request-Id": request_id},
    )


@app.middleware("http")
async def security_middleware(request: Request, call_next):
    request_id = request.headers.get("X-Request-Id", "").strip()[:80] or "req_" + secrets.token_hex(8)
    request.state.request_id = request_id
    client_host = request.client.host if request.client else "unknown"
    rate_key = f"{client_host}:{request.url.path}"
    if not allow_rate_limit(rate_key):
        with runtime.metrics_lock:
            runtime.metrics_counters["rate_limited"] += 1
        audit_event("rate_limit", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        return JSONResponse(
            status_code=429,
            content=response(code="RATE_LIMITED", message="请求过于频繁，请稍后重试", request_id=request_id),
            headers={"Retry-After": str(runtime.RATE_WINDOW_SECONDS), "X-Request-Id": request_id},
        )
    try:
        result = await call_next(request)
    except Exception:
        with runtime.metrics_lock:
            runtime.metrics_counters["errors"] += 1
        audit_event("request_error", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        raise
    result.headers["X-Request-Id"] = request_id
    result.headers["X-ReaMicro-API-Version"] = runtime.API_VERSION

    if request.url.path == "/admin" or request.url.path.startswith("/admin/"):
        result.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        result.headers["Pragma"] = "no-cache"
        result.headers["Expires"] = "0"
    with runtime.metrics_lock:
        runtime.metrics_counters["requests"] += 1
        runtime.metrics_routes[request.url.path] = runtime.metrics_routes.get(request.url.path, 0) + 1
    return result


@app.on_event("startup")
async def start_release_sync() -> None:
    runtime.get_state_store()

    canonicalize_owner_identities()
    if runtime.RUN_RELEASE_SYNC:
        asyncio.create_task(release_sync_loop())
    if runtime.RUN_SCHEDULER:
        recover_interrupted_tasks()

        migrate_device_tasks_to_server()
        asyncio.create_task(task_scheduler_loop())
        asyncio.create_task(server_snapshot_loop())
