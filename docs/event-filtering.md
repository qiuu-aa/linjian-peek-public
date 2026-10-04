# PEEPER_EVENT 候选过滤（candidate-v2）

v0.3.9.1 日常随机敲敲的设置、持久化和任务提示词补丁见 [casual-random-knock.md](casual-random-knock.md)。固定分级熬夜规则不增加设置入口。

## 完整链路与过滤位置

```text
Android AccessibilityEvent
  -> ScreenshotService
  -> ForegroundEventCoordinator
       -> ActivityEventStore.recordForegroundTrace(..., upload=false)  本机完整轨迹
       -> 4 秒稳定窗口
       -> 5 分钟滚动合并 / 语义阈值 / 冷却
       -> ActivityEventStore.emitCandidate(..., upload=true)            第一过滤边界
  -> POST /api/activity/events
  -> server/event_policy.py 或 Cloudflare Worker admission              旧客户端兜底
  -> server activity_events
  -> 外部 Slack / PEEPER_EVENT 转发器（只应消费 accepted=true 的候选）
  -> ChatGPT Work 条件任务
```

仓库内没有 Slack Webhook 实现；Android 的 POST 是当前代码中最早、也最可靠的 Slack 上游边界。新客户端不再上传普通原始前台切换。Python server 与 Cloudflare Worker 还会拒绝旧客户端的未聚合 `app_open`、系统/辅助界面、ChatGPT 打开和空 `phone_activity`，拒绝响应为 HTTP 202、`accepted=false`、`rule=<规则>`。

本机轨迹与上传候选已经分离。`ActivityEventStore` 仍保存原始包变化，包括系统设置和照片选择器，但这些记录带 `action=foreground_observed_local`、`metadata_json.local_only=true`，不会走网络上传。

## 配置项

阈值默认值集中在 `EventPolicyConfig`；可调整项通过 `AppPrefs` SharedPreferences 键读取：

| SharedPreferences 键 | 默认值 | 约束/含义 |
|---|---:|---|
| `event_foreground_stable_seconds` | 4 秒 | 强制限制在 3–5 秒 |
| `event_foreground_merge_minutes` | 5 分钟 | 普通前台切换滚动合并窗口 |
| `event_global_cooldown_minutes` | 15 分钟 | 普通上下文候选全局冷却 |
| `event_long_app_session_minutes` | 45 分钟 | 同一有效 App 连续会话阈值 |
| `event_shopping_min_minutes` | 2 分钟 | 购物/外卖稳定使用阈值 |
| `event_morning_inactive_minutes` | 240 分钟 | 早晨恢复活跃前的无活动阈值 |
| `event_afternoon_inactive_minutes` | 90 分钟 | 下午恢复活跃前的无活动阈值 |

固定时间窗口也集中在 `EventPolicyConfig`：Asia/Shanghai、早晨 06:00–11:00、下午 13:00–18:00、深夜 23:30–06:00、午饭 11:30–13:30、晚饭 17:30–20:00。饭点不增加轮询，只在下一次有效前台活动时判断。

深夜候选采用同一连续亮屏会话内的分级提醒：持续 10 分钟产生第 1 级（soft），累计 30 分钟产生第 2 级（firm），累计 60 分钟进入第 3 级（strict），之后每 30 分钟继续产生 strict 候选。ChatGPT 同样计入使用，切入/切出 ChatGPT 或切换其他 App 不清空计时与阶段，ChatGPT 前台也允许阶段候选。只有息屏、离开 23:30–06:00 窗口或无障碍服务生命周期重置才结束本轮。Slack 消息包含 `late_night_stage`、`reminder_tone` 和 `session_minutes`，下游可据此逐步收紧语气。普通 ChatGPT 打开事件仍被过滤，日常随机敲敲仍排除 ChatGPT；本修复不改这两条规则。

活动上传和 Slack 转发均不维护失败重试队列。断网期间发送失败的旧提醒不会在恢复网络后补发；若用户仍持续活跃，只会发送下一个到点的最新阶段。

## 包名分类

`PackageClassifier` 是 Android 端唯一分类表，按 `SYSTEM_UI`、`HELPER_UI`、`SETTINGS`、`CHATGPT`、`SHOPPING_TAKEOUT`、`TRAVEL_APP`、`MEANINGFUL` 分类。Launcher、System UI、导航/最近任务、锁屏/AOD/指纹、输入法、Resolver/分享面板、照片与文件选择器、权限/安装器、掌心窗自身都不会替换当前有效 App。

系统设置和健康使用设备只写本地。共享单车包明确排除。地图或交通 App 的打开不会生成 `travel_candidate`；只有调用 `recordTrustedTravelSignal` 并携带 `rail_ticket_confirmed`、`metro_trip_active`、`itinerary_confirmed` 或 `ride_in_progress` 才能生成行程候选。

## 事件兼容与迁移

普通切换摘要继续使用 `type=app_open`、`action=foreground_changed`，以兼容既有下游。聚合信息放在 `metadata_json`：

- `aggregated=true`
- `from_package`
- `to_package`
- `transition_count`
- `window_seconds`

新语义事件类型为：

- `morning_return_candidate`
- `afternoon_return_candidate`
- `late_night_active_candidate`
- `long_app_session_candidate`
- `shopping_or_takeout_candidate`
- `travel_candidate`
- `meal_window_candidate`
- `metric_threshold_candidate`

它们只是让下游模型获得一次判断机会，不携带固定提醒文案或屏幕正文。下游迁移时应允许这些类型，并继续忽略任何 `metadata_json.local_only=true` 的记录。对 `/api/activity/events` 做 Slack 转发的组件必须只转发 `accepted=true` 的请求/已保存事件，不能对 HTTP 202 的拒绝请求触发 Work 条件任务。

`phone_activity` 只有同时存在明确 `action` 或 `metadata_json.subtype` 才能上传。共同窗语和重要日历编辑保留；一分钟内的连续编辑使用 correlation ID 合并。电量、屏幕时间等数值只有调用 `recordThresholdMetric` 且跨越配置阈值时才形成候选。

归电展示不发事件；接受、拒绝和手动标记共用归电 action/correlation ID。同一归电过程的重复回执只保存一条；新增拒绝理由仍保留。明确请求、新留言、拒绝理由和需处理错误可绕过普通全局冷却。

## 旧行为 / 新行为

| 场景 | 旧行为 | candidate-v2 |
|---|---|---|
| App -> Launcher/System UI -> 原 App | 每次包变化都上传 | 本地保留，Slack 0 条 |
| App -> 照片选择器停留 -> 原 App | 选择器可能独立上传 | 附属操作，本地保留，Slack 0 条 |
| 多个有效 App 来回切换 | 逐条上传 | 5 分钟结束最多一条；首尾相同为 0 条 |
| 打开 ChatGPT | `app_open` | 只更新状态，本身 0 条 |
| 空 `phone_activity` | 直接上传 | 本地保存并记录 `suppressed_unknown_phone_activity` |
| 同一次归电多阶段回执 | 多条 | correlation ID 去重，最多一条 |
| 服务重启/无障碍重连 | 旧计时可能继续 | 所有进程内 pending timer 清空，不补发 |

## 隐私与可观察性

调试日志只记录规则、事件类型和包名/相关 ID，不记录屏幕正文、通知内容或输入内容。可见规则包括：

`cancelled_return_to_origin`、`ignored_system_ui`、`ignored_helper_ui`、`merged_repeat_switches`、`suppressed_chatgpt_open`、`suppressed_cooldown`、`suppressed_unknown_phone_activity`、`emitted_candidate`。

## 数量下降预期

用户给出的 81 条样本中约 60% 是 ChatGPT、Launcher、系统导航、AOD、照片选择器等直接命中过滤表的噪声，因此不考虑合并也应至少减少约 49 条。再叠加 4 秒稳定窗口、5 分钟首尾抵消、15 分钟冷却后，预计总体下降 **65%–85%**，即相同强度的一天约由 81 条降到 **12–28 条候选**。这是基于样本比例与仓库中的合成行为测试估计，不是线上实测；部署后应按 `event_policy rule=` 日志统计一整天再校准。
