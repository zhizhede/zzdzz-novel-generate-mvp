-- 流式输出开关（契约：全流程透明化 第1片）
-- 1=长文本节点（场景生成等）走 LLM 流式调用（MiniMaxClient.chatStream，SSE 增量实时推前端）；
-- 0=回退阻塞调用（行为与透明化改造前完全一致，排查/回退用）。
-- 仅影响传输形态：llm_call_log 记账（完整 request/response、精确 usage）两条路径同精度。
INSERT INTO tuning (tkey, tvalue, description)
VALUES ('stream_long_text', '1', '长文本节点（场景生成等）是否走 LLM 流式输出并实时推送前端；0=阻塞式（回退/排查用）')
ON CONFLICT DO NOTHING;
