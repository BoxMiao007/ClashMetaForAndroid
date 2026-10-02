# Triage 标签

skill 之间用五个标准 triage 角色沟通。本文件把这些角色映射到本仓库 issue 跟踪器里实际使用的标签字符串。

| mattpocock/skills 里的角色 | 本仓库的标签       | 含义                         |
| -------------------------- | ------------------ | ---------------------------- |
| `needs-triage`             | `needs-triage`     | 维护者需要评估这个 issue     |
| `needs-info`               | `needs-info`       | 等报告人补充信息             |
| `ready-for-agent`          | `ready-for-agent`  | 已完全明确,agent 可直接开工 |
| `ready-for-human`          | `ready-for-human`  | 需要人来实现                 |
| `wontfix`                  | `wontfix`          | 不处理                       |

本仓库选用默认词表:角色名即标签名,无需换算。skill 提到某个角色(如"打上 AFK-ready 的 triage 标签")时,使用表中对应的标签字符串。

标签在仓库里尚不存在时,由 `triage` 自动创建(`gh label create <名称> --repo BoxMiao007/ClashMetaForAndroid`)。
