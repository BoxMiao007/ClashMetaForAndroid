# Issue 跟踪:GitHub

本仓库的 issue 与 spec 放在 fork(origin)的 GitHub Issues 上,所有操作用 `gh` CLI。

- **仓库归属**:origin → `BoxMiao007/ClashMetaForAndroid`。issue 只建在 origin;**绝不**在 upstream(`MetaCubeX/ClashMetaForAndroid`)上建 issue、评论或加标签。
- `gh` 在仓库内会按 `git remote -v` 自动推断仓库;存在多个远程、可能有歧义时,显式加 `--repo BoxMiao007/ClashMetaForAndroid`。
- 标题与正文用简体中文。

## 约定

- **建 issue**:`gh issue create --repo BoxMiao007/ClashMetaForAndroid --title "..." --body "..."`,多行 body 用 heredoc。
- **读 issue**:`gh issue view <number> --repo BoxMiao007/ClashMetaForAndroid --comments`,评论用 `jq` 过滤,并取 labels。
- **列 issue**:`gh issue list --repo BoxMiao007/ClashMetaForAndroid --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`,按需加 `--label` / `--state` 过滤。
- **评论**:`gh issue comment <number> --repo BoxMiao007/ClashMetaForAndroid --body "..."`
- **加/去标签**:`gh issue edit <number> --repo BoxMiao007/ClashMetaForAndroid --add-label "..."` / `--remove-label "..."`
- **关闭**:`gh issue close <number> --repo BoxMiao007/ClashMetaForAndroid --comment "..."`

## PR 作为 triage 队列

**PR 作为请求入口:否。** _(若本仓库把外部 PR 当作功能请求处理,改为 `yes`,`/triage` 会读此开关。)_

设为 `yes` 时,PR 走与 issue 相同的标签与状态,用 `gh pr` 等价命令:

- **读 PR**:`gh pr view <number> --comments`,diff 用 `gh pr diff <number>`。
- **列外部 PR 供 triage**:`gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`,只保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的(排除 `OWNER`/`MEMBER`/`COLLABORATOR`)。
- **评论/标签/关闭**:`gh pr comment`、`gh pr edit --add-label`/`--remove-label`、`gh pr close`。

GitHub 的 issue 与 PR 共用一个编号空间,裸 `#42` 可能指两者:先用 `gh pr view 42` 解析,失败再用 `gh issue view 42`。

## 当 skill 说 "publish to the issue tracker"

在 origin 建一个 GitHub issue。

## 当 skill 说 "fetch the relevant ticket"

`gh issue view <number> --repo BoxMiao007/ClashMetaForAndroid --comments`。

## Wayfinder 操作

`/wayfinder` 使用。**map** 是一个 issue,**child** 子 issue 作为其工单。

- **Map**:单个打了 `wayfinder:map` 标签的 issue,承载 Notes / Decisions-so-far / Fog 正文。`gh issue create --repo BoxMiao007/ClashMetaForAndroid --label wayfinder:map`。
- **子工单**:作为 GitHub sub-issue 挂到 map 下(`gh api` 调 sub-issues 端点);sub-issues 不可用时,在 map 正文的任务列表里加上该子项,并在子工单正文顶部写 `Part of #<map>`。标签:`wayfinder:<type>`(`research`/`prototype`/`grilling`/`task`)。工单被认领后 assign 给执行的 dev。
- **阻塞关系**:用 GitHub 原生 issue 依赖(UI 可见的权威表示)。加边:`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`,其中 `<blocker-db-id>` 是阻塞者的数字 **database id**(`gh api repos/<owner>/<repo>/issues/<n> --jq .id`,不是 `#number` 或 `node_id`)。GitHub 通过 `issue_dependencies_summary.blocked_by`(仅 open 阻塞者,实时门槛)上报。依赖不可用时,降级为在子工单正文顶部写 `Blocked by: #<n>, #<n>`。所有阻塞者关闭即视为解锁。
- **Frontier 查询**:列出 map 的 open 子工单(限定在 map 的 sub-issues / 任务列表范围),去掉仍有 open 阻塞者(`issue_dependencies_summary.blocked_by > 0`,或 `Blocked by` 行里有 open issue)或已有 assignee 的;按 map 顺序取第一个。
- **认领**:`gh issue edit <n> --add-assignee @me`,作为本次会话的第一次写操作。
- **解决**:`gh issue comment <n> --body "<answer>"`,然后 `gh issue close <n>`,再把上下文指针(gist + 链接)追加到 map 的 Decisions-so-far。
