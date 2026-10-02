# 域文档

工程类 skill 在探索代码库时应如何消费本仓库的域文档。

**布局:单上下文**——根目录一份 `GLOSSARY.md` + 一份 `docs/adr/`。

## 探索前先读

- 根目录 `GLOSSARY.md`(存在时);
- 根目录 `GLOSSARY-MAP.md`(存在时):它指向每个上下文各自的 `GLOSSARY.md`,读与主题相关的那些;
- `docs/adr/`:读与你即将改动的区域相关的 ADR。

这些文件不存在时**静默继续**:不要提示缺失,也不要建议预先创建。`/domain-modeling` skill(经 `/grill-with-docs` 与 `/improve-codebase-architecture` 触达)会在术语或决策真正敲定时惰性创建它们。

## 使用术语表的词汇

输出中提到领域概念(issue 标题、重构提案、假设、测试名)时,用 `GLOSSARY.md` 里定义的术语,不要漂移到术语表明确避开的同义词。

需要的概念还不在术语表里,本身就是一个信号:要么你在发明项目不用的语言(重新考虑),要么确实有缺口(记下来交给 `/domain-modeling`)。

## 标记 ADR 冲突

输出与既有 ADR 矛盾时,显式指出而不是悄悄覆盖:

> _与 ADR-0007(event-sourced orders)矛盾,但值得重开,因为……_
