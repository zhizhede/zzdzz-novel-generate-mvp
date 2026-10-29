# DeriveFlow Refactor Prompt Pack

Generated from the three execution plans on 2026-10-28.

## Use

For every task, launch a fresh implementation context with workers/<TASK_ID>.md.
After it completes, launch a separate fresh review context with reviewers/<TASK_ID>.md.
Only continue after the reviewer returns APPROVE.

Execution order:

A1, A2, A3, A4, A5, A6, A7, A8, A9, A10,
B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12,
C1, C2, C3, C4, C5, C6, C7, C8, C9, C10, C11.

Coordinator prompt:

E:\Program\JavaGuide\Codes\zzdzz-novel-generate-mvp\docs\superpowers\plans\2026-10-28-derive-refactor-executor-prompt.md

## Task prompt files

- A1: workers/A1.md | reviewers/A1.md
- A2: workers/A2.md | reviewers/A2.md
- A3: workers/A3.md | reviewers/A3.md
- A4: workers/A4.md | reviewers/A4.md
- A5: workers/A5.md | reviewers/A5.md
- A6: workers/A6.md | reviewers/A6.md
- A7: workers/A7.md | reviewers/A7.md
- A8: workers/A8.md | reviewers/A8.md
- A9: workers/A9.md | reviewers/A9.md
- A10: workers/A10.md | reviewers/A10.md
- B1: workers/B1.md | reviewers/B1.md
- B2: workers/B2.md | reviewers/B2.md
- B3: workers/B3.md | reviewers/B3.md
- B4: workers/B4.md | reviewers/B4.md
- B5: workers/B5.md | reviewers/B5.md
- B6: workers/B6.md | reviewers/B6.md
- B7: workers/B7.md | reviewers/B7.md
- B8: workers/B8.md | reviewers/B8.md
- B9: workers/B9.md | reviewers/B9.md
- B10: workers/B10.md | reviewers/B10.md
- B11: workers/B11.md | reviewers/B11.md
- B12: workers/B12.md | reviewers/B12.md
- C1: workers/C1.md | reviewers/C1.md
- C2: workers/C2.md | reviewers/C2.md
- C3: workers/C3.md | reviewers/C3.md
- C4: workers/C4.md | reviewers/C4.md
- C5: workers/C5.md | reviewers/C5.md
- C6: workers/C6.md | reviewers/C6.md
- C7: workers/C7.md | reviewers/C7.md
- C8: workers/C8.md | reviewers/C8.md
- C9: workers/C9.md | reviewers/C9.md
- C10: workers/C10.md | reviewers/C10.md
- C11: workers/C11.md | reviewers/C11.md
