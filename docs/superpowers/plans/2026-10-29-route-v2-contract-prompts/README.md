# ROUTE v2 A6V2/A6B Prompt Pack

## 执行顺序

```text
A6V2 Worker
→ A6V2 Reviewer APPROVE
→ A6B Worker
→ A6B Reviewer APPROVE
```

不要再使用旧 A6 Worker/Reviewer。

## Workers

- `workers/A6V2.md`
- `workers/A6B.md`

## Reviewers

- `reviewers/A6V2.md`
- `reviewers/A6B.md`

所有 Prompt 均使用绝对工作区路径，且明确要求执行者逐项报告 Boundary、Inputs、Outputs、Pass Criteria 和测试证据。
