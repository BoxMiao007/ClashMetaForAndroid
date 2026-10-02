---
status: accepted
---

# 0001 — 与 clash-verge-rev 的 WebDAV 互通采用其原生备份包格式

需求是 CMFA 与 clash-verge-rev 经同一个 WebDAV 账号双向同步订阅,而 clash-verge-rev 是官方发布的应用、不可改动。我们决定 CMFA 直接读写 verge 的原生备份格式:解析其固定同步目录 `clash-verge-rev-backup/` 下最新 zip 的订阅索引(profiles.yaml)与订阅内容文件;推送时生成 `android-backup-<时间戳>.zip` 上传到同一目录,verge 不做任何改动即可在历史列表中看到并恢复它。CMFA 无法表达的条目(verge 增强文件、External 类型订阅)推送时原样保留,CMFA 生成的包不含 verge 的设置文件,verge 恢复后其自身设置不受影响。

## Considered Options

- 自定义中立同步格式:verge 识别不了,不满足兼容需求,放弃。
- 修改 clash-verge-rev 引入自定义格式:违背「官方端不动」的前提,放弃。

## Consequences

- 与 verge 内部格式演进绑定(基于其 v2.5.x 的行为),verge 改备份格式时需跟版适配。
- 推送时必须保留包内 CMFA 不理解的条目,否则 verge 端的增强配置会被静默丢弃。
- CMFA 的订阅 providers 文件以 `cmfa-providers/<内容文件名>/` 前缀放入备份包(verge 视作普通文件原样落盘,CMFA 恢复时按前缀读回)——这是本 fork 的自有约定,verge 不感知。
