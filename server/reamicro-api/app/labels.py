"""枚举与标识的中文显示名。

后台页面和任务消息标题共用同一套文案，避免同一个状态在两处显示成不同说法。
本模块只做纯映射，不读配置也不碰存储。
"""
from typing import Any

from app import state


def _admin_kind_label(kind: str) -> str:
    return {"online_source": "书源", "association_source": "关联源", "epub_style": "EPUB 样式", "highlight_style": "高亮样式", "theme": "主题库"}.get(kind, kind)


def _admin_task_label(task_type: Any) -> str:
    return {
        "yeshe_checkin": "野社零点签到",
        "yeshe_draw_card": "野社自动抽卡",
        "cloud_auto_read": "云端自动阅读",
        "http": "通用 HTTPS 请求",
    }.get(str(task_type), str(task_type) or "未命名任务")


def _admin_task_status_label(status: Any) -> str:
    return {
        "scheduled": "等待执行",
        "running": "执行中",
        "success": "执行成功",
        "failed": "执行失败",
        "paused": "已暂停",
        "cancelled": "已取消",
    }.get(str(status), str(status) or "未知")


def _admin_health_label(health: Any) -> str:
    return {
        "valid": "有效",
        "warning": "需要验证",
        "invalid": "已失效",
        "paused": "已暂停",
        "unverified": "未验证",
    }.get(str(health), str(health) or "未验证")


def _admin_owner_label(owner: Any) -> str:
    text = str(owner or "")
    if text.startswith("host:") or ":host:" in text or text.startswith("host-public:"):
        account_id = state.owner_host_account_id(text)
        return f"阅微账号 {account_id}" if account_id else "阅微账号认证"
    if text.startswith("account:"):
        return f"独立账号：{text.split(':', 1)[1]}"
    if text.startswith("key:"):
        return "API Key 用户"
    return text or "未标记"


def _admin_status_label(status: Any) -> str:
    return {
        "published": "已发布",
        "draft": "草稿",
        "testing": "测试中",
        "unpublished": "已下架",
    }.get(str(status), str(status) or "已发布")


def _admin_channel_label(channel: Any) -> str:
    return {"stable": "正式版", "beta": "预发布", "nightly": "每夜构建"}.get(str(channel), str(channel) or "正式版")


def _admin_result_label(result: Any) -> str:
    return {"success": "成功", "failed": "失败", "skipped": "已跳过", "cancelled": "已取消"}.get(str(result), str(result) or "未知")


def _admin_action_label(action: Any) -> str:
    """审计事件转中文说明。未收录的动作原样显示，便于排查新事件。"""
    return {
        "admin_login_success": "管理员登录成功",
        "admin_login_failed": "管理员登录失败",
        "admin_settings_updated": "修改服务器设置",
        "admin_csrf_rejected": "后台请求校验失败",
        "admin_setup_completed": "完成主管理员初始化",
        "subadmin_created": "创建子管理员",
        "subadmin_password_reset": "重置子管理员密码",
        "subadmin_toggled": "启用或停用子管理员",
        "subadmin_deleted": "删除子管理员",
        "api_key_created": "创建 API Key",
        "api_key_revoked": "吊销 API Key",
        "secret_key_rotated": "轮换服务器加密密钥",
        "secret_key_rotation_failed": "轮换加密密钥失败",
        "package_uploaded": "上传内容包",
        "package_edited": "编辑内容包",
        "package_deleted": "删除内容包",
        "package_rolled_back": "回滚内容包",
        "module_upload_created": "模块上传新内容",
        "module_upload_linked": "模块关联已有内容",
        "module_upload_denied": "模块上传被拒绝",
        "server_snapshot_created": "创建服务器快照",
        "server_snapshot_restored": "恢复服务器快照",
        "rate_limit": "触发请求限流",
        "request_error": "请求处理异常",
    }.get(str(action), str(action) or "未知操作")


_ADMIN_METADATA_LABELS = {
    "kind": "类型",
    "packageId": "内容包",
    "version": "版本",
    "status": "状态",
    "username": "用户名",
    "keyId": "密钥 ID",
    "permissions": "权限",
    "filename": "文件",
    "serverId": "服务器 ID",
    "hostAccountId": "阅微账号",
    "path": "路径",
    "enabled": "启用",
    "reencrypted": "重新加密条目",
    "safetySnapshot": "安全快照",
}


def _admin_metadata_label(metadata: Any) -> str:
    """审计详情转成可读的"键：值"串，代替裸 JSON。"""
    if not isinstance(metadata, dict) or not metadata:
        return "无附加信息"
    parts: list[str] = []
    for key, value in metadata.items():
        label = _ADMIN_METADATA_LABELS.get(str(key), str(key))
        if key == "kind":
            text = _admin_kind_label(str(value))
        elif key == "status":
            text = _admin_status_label(value)
        elif isinstance(value, bool):
            text = "是" if value else "否"
        elif isinstance(value, (list, tuple)):
            text = "、".join(str(item) for item in value) or "无"
        else:
            text = str(value)
        parts.append(f"{label}：{text}")
    return "；".join(parts)


# 语义色调。后台所有状态徽标都通过 status_tone 归到这五种之一，
# 而不是把原始枚举值当 CSS 类名——那样新增一个枚举值就会渲染成无样式的灰块，
# 而且很难发现（不报错，只是看起来没上色）。
TONE_OK = "ok"
TONE_WARN = "warn"
TONE_BAD = "bad"
TONE_IDLE = "idle"
TONE_INFO = "info"

_STATUS_TONES = {
    # 内容包发布状态
    "published": TONE_OK,
    "draft": TONE_WARN,
    "testing": TONE_WARN,
    "unpublished": TONE_IDLE,
    # 任务与执行结果
    "success": TONE_OK,
    "running": TONE_INFO,
    "scheduled": TONE_INFO,
    "failed": TONE_BAD,
    "paused": TONE_IDLE,
    "cancelled": TONE_IDLE,
    "skipped": TONE_IDLE,
    # 凭据健康
    "valid": TONE_OK,
    "invalid": TONE_BAD,
    "unverified": TONE_WARN,
    # 在线状态
    "online": TONE_OK,
    "offline": TONE_IDLE,
    # 书源可用性
    "ok": TONE_OK,
    "slow": TONE_WARN,
    "unreachable": TONE_BAD,
    "blocked": TONE_BAD,
    # 发布渠道
    "stable": TONE_OK,
    "beta": TONE_WARN,
    "nightly": TONE_INFO,
    # 通用
    "warning": TONE_WARN,
    "enabled": TONE_OK,
    "disabled": TONE_IDLE,
}


def status_tone(value: Any) -> str:
    """把任意状态值归到一种语义色调。未收录的值走中性色，不会变成无样式的灰块。"""
    return _STATUS_TONES.get(str(value or "").strip().casefold(), TONE_INFO)


def status_badge(value: Any, text: Any = None) -> str:
    """渲染一个状态徽标。所有徽标都该走这里，保证配色与圆角一致。"""
    import html

    label = str(text if text is not None else value)
    return f"<span class='status tone-{status_tone(value)}'>{html.escape(label, quote=True)}</span>"
