"""ReaMicro API 服务器的应用装配。

本文件只做四件事：建 FastAPI 应用、装中间件与异常处理、注册各域 router、挂启动钩子。
业务逻辑按域分布在其他模块：

    runtime        路径、环境变量与可变全局
    responses      统一响应包裹
    audit          审计事件与任务日志
    config_store   配置读写与认证模式推断
    crypto         密钥派生、对称加密与口令哈希
    state          任务/消息/在线/凭据持久化，以及归属折叠迁移
    labels         枚举与标识的中文显示名
    security       认证、授权、会话、CSRF、限流、归属解析
    packages       内容包清单、身份复用、元数据推断与签名
    releases       GitHub Release 同步
    backups        服务器快照与加密密钥轮换
    executors      云端任务的实际执行
    scheduler      调度循环与执行记账
    admin/         后台展示层（format 格式化、layout 外壳、views 各分区 HTML）
    api/           /v1 与 /admin 路由，按域分文件

**测试注意**：可变全局（路径、密钥等）住在 `app.runtime`，要重定向数据目录得改
`runtime.X`；改 `main.X` 不会生效，因为各模块读的是 runtime 上的属性。
"""
import asyncio
import secrets
from typing import Any

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
    recover_interrupted_tasks,
    release_sync_loop,
    server_snapshot_loop,
    task_scheduler_loop,
)
from app.security import allow_rate_limit
from app.state import canonicalize_owner_identities

app = FastAPI(title="ReaMicro API", version=runtime.API_VERSION)

# 注册顺序决定路径匹配优先级：更具体的路径必须先注册，否则会被带路径参数的路由吃掉。
# 例如 /v1/packages/upload/policy 必须早于 /v1/packages/{kind}/{id}/download。
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
    """判断是否应该给后台浏览器返回 HTML 错误页而不是 JSON。"""
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
        # 后台页面用浏览器访问，错误要以可读页面呈现；WWW-Authenticate 之类的头继续保留。
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
    # 后台页面包含账号、凭据状态和 CSRF，禁止浏览器及反向代理复用旧 HTML。
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
    # 认证模式变更过的服务器会留下多套归属写法，先折叠再启动调度，避免任务按旧归属重复执行。
    canonicalize_owner_identities()
    if runtime.RUN_RELEASE_SYNC:
        asyncio.create_task(release_sync_loop())
    if runtime.RUN_SCHEDULER:
        recover_interrupted_tasks()
        asyncio.create_task(task_scheduler_loop())
        asyncio.create_task(server_snapshot_loop())
