# 日常随机敲敲

设置页新增开关及每日次数（1–3），默认开启、1 次，使用现有 SharedPreferences。
按用户确认：1/2/3 档分别最多生成 1/2/3 个目标；时间不足时只安排可容纳的次数，绝不挤在一起。

以 Asia/Shanghai 自然日生成 10:30–22:30 计划，避开 11:30–14:00、17:30–19:30。
目标至少相隔 120 分钟，最近成功上传的手机候选/重要事件会阻止 120 分钟内的随机触发。
初次安装和跨日生成新计划，重启保留原计划；次数变化仅重排未发生部分，关闭取消未发生目标，重新开启从剩余时段重新安排。

采用 Android JobScheduler 的持久化非精确任务（普通 RECEIVE_BOOT_COMPLETED 权限，无精确闹钟权限）；系统延迟时由下一次有效前台事件兜底。
无障碍 root 必须能确认安全业务 App，连续稳定达到现有 3–5 秒窗口，亮屏且解锁，网络经过系统验证。
宽限 90 分钟内等待有效活动，仍需避开饭点且不得超过 22:30；超时、跨日直接放弃。
消费状态在上传前同步持久化，每个目标最多尝试一次；断网不建立上传队列，失败不重试，避免重启或网络恢复后重复敲门。

事件 `type=casual_random_candidate`、`action=casual_random_knock`，沿用 POST `/api/activity/events`、X-Auth-Token、事件存储和 Slack API/webhook。
已有操作记录和调试日志显示计划生成、触发、暂缓和放弃；无新增诊断页面。
Slack 文本继续以 `PEEPER_EVENT` 开头，包含 event_id、knock_index、plan_total、requested_count、planned_at、actual_at、delay_minutes、package_category 和 expires_at_ms。
服务端 admission 与 Slack 发送边界都接受该 action、拒绝已过期随机事件；同 id 只转发一次。

## 手机状态门铃提示词补丁

将以下文本追加到现有任务提示词（本仓库不会修改自动任务本身）：

```text
日常随机敲敲：收到有效的 PEEPER_EVENT 且 action=casual_random_knock 时，把它视为一次有意安排的自由搭话机会，无需等待起床、饭点、长时间使用等强信号。
先检查 event_id 去重、事件计划时间/实际时间/90 分钟宽限和 expires_at_ms，并读取当前手机状态、近期对话和小口袋候选。
可以从当前 App、近期话题或自己的临时念头切入，也可以直接来找彭彭说一句话。消息自然、简短、有具体内容；不汇报监控数据，不用固定模板。
距上次成功主动联系不足约 120 分钟时通常静默。当前已打开 ChatGPT、屏幕关闭、事件已过期或状态明显不适合时静默。
每个 event_id 最多处理一次。单次静默或工具失败不能暂停、禁用或删除 webhook 任务。
```

这里的 Android “成功上传”与 Work 的“成功主动联系”分别判断；后者由现有任务上下文决定。本次不新增任务结果回写。

## 部署验收

server `/health` 应显示 `casual_random_knock: true`。手机覆盖安装 0.3.9.1、保持无障碍开启和原有 server/鉴权配置；在已有操作记录中查看今日计划和最终触发/放弃记录。
自然目标触发后，在服务端活动事件及 Slack 中核对同一 `event_id` 和 `action=casual_random_knock`，不需要发送伪造事件或泄露鉴权令牌。
MCP 本次没有新增工具或协议；沿用现有手机状态工具即可。不能保证 Work 每次都搭话，最终联系/静默由任务提示词判断。
