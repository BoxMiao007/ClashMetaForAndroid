# Agent 开发约定(本 fork)

本文件面向在本仓库工作的 AI agent,由 fork 维护者维护,与上游无关。
上游同步合并时,本文件若与上游版本冲突,一律保留本文件版本,不要恢复上游内容。

## 项目是什么

ClashMetaForAndroid(CMFA)——Clash.Meta 内核的 Android 代理客户端。本仓库是 MetaCubeX/ClashMetaForAndroid 的个人 fork,以 Vibe Coding 方式维护:维护者只提需求和验收,所有实现、验证、提交由 AI agent 完成;不向上游提交。

## 协作模式

- 用户的话按"需求与验收期望"理解,不是技术指令;技术选型、实现细节、文件组织由 agent 决定。
- 方案不唯一且影响后续走向(技术栈、数据模型、外部服务、付费依赖)时,用简体中文给 2~3 个带取舍的选项让用户选;能安全推断的直接做,做完说明选了什么、为什么。
- 需求有歧义且无法从代码、文档或常识推断时,一次问清并附默认建议;不问只有程序员才能答的技术判断题。

## 完成定义(每次交付前逐项核对)

1. 验证门全部通过:`./gradlew compileAlphaDebugKotlin`(所有模块的 Kotlin 编译与类型检查;实测首次全量约 2 分钟,增量约 5 秒)。
2. 功能实际跑通:本机无常驻设备/模拟器,agent 的"跑通"标准是完整打包成功;装到手机实测由用户完成,交付时附 APK 路径。涉及界面或输出的改动,附截图或运行结果作为交付证据;设备已连接时(adb devices 可见)可装机自测。
3. 交付说明讲给不写代码的人听:做了什么、怎么装/怎么看效果、有什么限制和后续建议。
4. 未完成或没把握的部分明说,不藏在细节里。

## 提交与推送

- 不自动提交:完成并验证后报告,用户说提交才提交。
- 不自动推送:用户说"推"才推到 `origin`(上游同步流程的推送也遵循此项);绝不向 `upstream` 推送。
- 密钥、凭据、本地产物(`.env`、`*.pem`、`*.key`、token等)不提交;`.gitignore` 保持覆盖。

## 需求与进度

- 一次需求包含多个可独立验证的改动时,先列任务清单再动手,完成一项勾一项,让用户随时看到进度。
- 用户问"现在什么状态"时,答:已完成什么、在做什么、下一步、卡在哪。

## 构建环境(2026-10-01 搭好;重装或换机照此执行)

| 组件 | 位置 | 说明 |
| ---- | ---- | ---- |
| JDK 21 | `~/.jdks/jdk-21.0.12.1+1` | 经 `~/.gradle/gradle.properties` 的 `org.gradle.java.home` 生效;系统默认 Java 25 会被 Gradle 8.10.2 拒绝 |
| Android SDK | `~/Android/Sdk` | platforms/android-35、build-tools/34.0.0 与 35.0.0、platform-tools、ndk/29.0.14206865;licenses 已接受;仓库 `local.properties`(gitignored)指向它 |
| Gradle 代理 | `~/.gradle/gradle.properties` | systemProp 指向 `127.0.0.1:7890`;Gradle 不读 http_proxy 环境变量 |
| Go(编译 core) | `~/go-toolchains/go1.26-patched` | MetaCubeX go1.26 构建 + 仓库 `.github/patch/` 的两个补丁(补 Android 32 位运行时);编译 core 前置于 PATH |
| 子模块 | `core/src/foss/golang/clash` | mihomo Alpha 分支;新 clone 后先 `git submodule update --init --recursive` |

- 国内镜像更快:Android SDK 组件(NDK/platform/build-tools)从 `https://mirrors.cloud.tencent.com/AndroidSDK/` 直连下载;GitHub 类资源(Gradle 发行包、Go 构建、geo 文件)走代理。
- 项目没有单元测试,验证门不含 test 任务。

## 完整打包(慢,仅需要产物时执行)

- `export PATH="$HOME/go-toolchains/go1.26-patched/bin:$PATH"` 然后 `./gradlew app:assembleAlphaRelease`;实测约 9 分钟。
- 产物:`app/build/outputs/apk/alpha/release/cmfa-<版本>-alpha-<abi>-release.apk`(universal + 各 ABI)。
- clean 后重新打包会先执行 `downloadGeoFiles`,从 GitHub 下载 geoip/geosite/ASN/BundleMRS(约百 MB)到 `app/src/main/assets`(gitignored)。
- 仓库无 `signing.properties`,release 包用 debug 签名(上游如此);需要正式签名时自建 `signing.properties` + 私钥,不要提交。

## 知识沉淀(保证任何新会话都能接手)

- 全新环境照「构建环境」节的表格逐项搭建;步骤变化时同步更新该节。
- 重大技术决策(选型、数据模型、外部服务)在 `docs/decisions.md` 记一行:日期、决策、为什么、放弃了什么。
- README 面向用户维护:项目是什么、怎么启动、日常怎么用,保持非程序员能看懂。

## 分支模型

- `main` 是集成分支:只接受两类提交——合并上游 `upstream/main`、合并功能分支。全局文档改动(如本文件)可直接提交在集成分支。
- 新功能一律在 `feat/<功能名>` 短命分支上开发,完成后合回集成分支。
- 推送目标始终是 `origin`(自己的 fork),绝不向 `upstream` 推送。

## 上游同步

- 上游:`upstream` → https://github.com/MetaCubeX/ClashMetaForAndroid,默认分支 `main`。
- 节奏:每 1~2 周或上游发版后同步一次,不长期积压。
- **合并前:**
  - 工作区必须干净;有未提交改动先提交或 stash,并向用户说明放到了哪里。
  - 显式 `git fetch upstream`,不用 `git pull`。
  - 先总结 `upstream/main` 新提交,并对照下方「功能清单」标注可能与本 fork 功能重叠的文件。
  - 若发现上游历史被重写(上次合并点不再是 `upstream/main` 的祖先),停下向用户确认,不强行合并。
- **合并中:**
  - 冲突逐个解决:先弄清两边意图再融合;本 fork 已有功能不可被丢弃或绕过;禁止为省事整体取单边。
  - 无法确定取舍时停下向用户确认,不猜。
  - 本文件(`AGENTS.md`)冲突一律保留本 fork 版本。
  - `README.md` 顶部的 `vibe-coding-declaration` 与 `vibe-feature-registry` 标记块一律整体保留,其余内容取上游。
  - 合并搞砸可 `git merge --abort` 完整回到合并前状态,已提交的内容不受影响。
- **合并后:**
  - 依赖清单 `gradle/libs.versions.toml` 有变更时,Gradle 下次构建自动拉新依赖,无需手动安装。
  - 验证门:`./gradlew compileAlphaDebugKotlin`。全部通过才算完成。
  - `./gradlew app:assembleAlphaRelease` 是完整打包(约 9 分钟),只在需要产出安装包时执行,不作为每次同步的验证步骤。
  - 验证门通过后做功能巡检:用 `git diff <上一个 sync 标签>..upstream/main --name-only` 列出本次同步引入的上游改动,对照「功能清单」的关键文件,输出受影响功能报告——波及哪些功能、判断依据、建议用户在应用里实际验证的点;没有历史标签时以本次合并的 merge-base 为基准。
  - 巡检完成后打标签 `sync/<YYYY-MM-DD>`;是否推送遵循「提交与推送」节策略,只能推 `origin`,绝不推 `upstream`。

## 功能清单

本节是功能登记的**唯一事实源**:新功能合入集成分支时在此登记一行,功能下线时移除。agent 需要了解本 fork 有哪些功能时只读本节即可,不要为读功能清单去打开 README(那里只是给人看的镜像)。代码演变见 git 历史(`git log upstream/main..main --no-merges`)。

| 功能 | 一句话说明 | 关键文件/入口 | 引入提交 |
| ---- | ---------- | -------------- | -------- |
| WebDAV 订阅双向同步 | 与 clash-verge-rev 经同一 WebDAV 双向同步订阅:并集合并、删除传播、冲突弹窗、云端历史管理;HTTP 传输走 Go 内核(绕坚果云 TLS 指纹拦截,见 docs/adr/0002) | 设置→同步(service/sync、common/sync、service/webdav、core webdav 桥) | feat/webdav-sync(c6d35833) |

`README.md` 顶部的 `vibe-feature-registry` 标记块是本表给人看的镜像:登记/移除功能时,同一个动作里同步更新本表和该标记块,防止两处漂移。

## 降低合并冲突的约定

- 能通过新增文件接入的功能,不修改上游既有文件。
- 提交信息写清改动意图(做什么、为什么),便于合并冲突时判断取舍。
- 本 fork 内提交信息用简体中文;如向上游提交 PR,遵循上游仓库的契约(如英文提交、Conventional Commits)。

## Agent skills

本节是工程类 skill(to-tickets、triage、to-spec、grill-with-docs 等)的配置入口;三份配置文件可直接改,改动即生效。

### Issue tracker

issue 跟踪在 fork(origin → `BoxMiao007/ClashMetaForAndroid`)的 GitHub Issues 上,用 `gh` CLI 读写,只在 origin 操作、绝不碰 upstream。见 `docs/agents/issue-tracker.md`。

### Triage labels

用五个默认 triage 标签:needs-triage、needs-info、ready-for-agent、ready-for-human、wontfix。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局:根目录 `GLOSSARY.md` + `docs/adr/`,文件不存在时静默继续。见 `docs/agents/domain.md`。
