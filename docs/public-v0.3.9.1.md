# 掌心窗 v0.3.9.1

- 新增可配置日常随机敲敲，默认开启、每天一次。
- 计划按上海自然日持久化；可选每天最多 1–3 次，目标至少相隔两小时。
- 10:30–22:30 避开午晚饭，稳定有效前台、亮屏解锁及联网才触发，90 分钟后放弃且不补发。
- 复用已有操作记录、上传、鉴权、Slack 链路，不增加诊断页面或任务回写。
- 固定熬夜规则继续使用 23:30–06:00、10/30/60 分钟 soft/firm/strict，之后每 30 分钟；相关常量与代码未改动。
- Android 版本 0.3.9.1 / 30901，固定公开证书指纹保持不变。

正式 APK：https://github.com/qiuu-aa/linjian-peek-public/releases/download/v0.3.9.1/Zhangxinchuang-public-v0.3.9.1.apk

CI 在发布正式签名 APK 后计算实际 SHA-256，上传 `.apk.sha256`，并写回根目录 `update.json`；升级下载使用该实际摘要校验。

设置和可直接追加的任务提示词：[日常随机敲敲](casual-random-knock.md)。
