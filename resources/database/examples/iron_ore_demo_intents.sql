-- 铁矿工业知识 Demo 意图配置。
-- 手工执行；脚本要求目标库中已经存在且仅存在一个名为“铁矿检测演示库”的知识库。
-- 四个互斥入口覆盖：闲聊、文档检索、确定性版本比较、批准后任务模拟。

DO $$
BEGIN
    IF (SELECT count(*) FROM t_knowledge_base WHERE name = '铁矿检测演示库' AND deleted = 0) <> 1 THEN
        RAISE EXCEPTION '执行前请先创建且仅保留一个名为“铁矿检测演示库”的知识库';
    END IF;
END $$;

INSERT INTO t_intent_node (
    id, kb_id, intent_code, name, level, parent_code, description, examples,
    collection_name, collection_names, top_k, mcp_tool_id, kind,
    prompt_snippet, prompt_template, param_prompt_template,
    sort_order, enabled, create_by, update_by, create_time, update_time, deleted
) VALUES (
    '20260812000000000001', NULL, 'iron-ore-chat', '铁矿演示闲聊', 0, NULL,
    '问候、能力介绍以及不需要查阅文档或调用工具的一般交流。',
    '["你好","你能做什么","介绍一下这个演示"]',
    NULL, '[]'::jsonb, NULL, NULL, 1,
    NULL, NULL, NULL,
    10, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
), (
    '20260812000000000003', NULL, 'iron-ore-version-diff', '铁矿文档版本比较', 0, NULL,
    '比较铁矿石人工检测流程调研 XLSX 的两个明确版本，列出确定性的单元格变化并说明可能影响。',
    '["比较 V1.2 和 V1.3-demo","新版流程改了什么","解释演示版本的三处变化"]',
    NULL, '[]'::jsonb, NULL, 'iron_ore_compare_versions', 2,
    NULL, NULL, NULL,
    30, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
), (
    '20260812000000000004', NULL, 'iron-ore-task-simulate', '候选任务模拟', 0, NULL,
    '对已经人工批准的候选任务模板生成模拟执行事件；不连接、不控制真实设备。',
    '["模拟执行任务模板 123","运行已批准的候选任务","演示任务执行过程"]',
    NULL, '[]'::jsonb, NULL, 'iron_ore_simulate_task', 2,
    NULL, NULL, NULL,
    40, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
)
ON CONFLICT (id) DO UPDATE SET
    intent_code = EXCLUDED.intent_code,
    name = EXCLUDED.name,
    level = EXCLUDED.level,
    parent_code = EXCLUDED.parent_code,
    description = EXCLUDED.description,
    examples = EXCLUDED.examples,
    collection_name = EXCLUDED.collection_name,
    collection_names = EXCLUDED.collection_names,
    top_k = EXCLUDED.top_k,
    mcp_tool_id = EXCLUDED.mcp_tool_id,
    kind = EXCLUDED.kind,
    prompt_snippet = EXCLUDED.prompt_snippet,
    prompt_template = EXCLUDED.prompt_template,
    param_prompt_template = EXCLUDED.param_prompt_template,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    update_by = EXCLUDED.update_by,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;

INSERT INTO t_intent_node (
    id, kb_id, intent_code, name, level, parent_code, description, examples,
    collection_name, collection_names, top_k, mcp_tool_id, kind,
    prompt_snippet, prompt_template, param_prompt_template,
    sort_order, enabled, create_by, update_by, create_time, update_time, deleted
)
SELECT
    '20260812000000000002', kb.id, 'iron-ore-procedure', '铁矿规程检索', 0, NULL,
    '查阅人工检测流程、排班、前置条件、操作步骤、质量判据、异常处理或安全约束；所有结论必须来自文档。',
    '["中心化验室浓度检测的烘干称量步骤是什么","提取流程的前置条件和质量判据","根据检索结果生成候选任务草案","排班表如何安排"]',
    kb.collection_name, jsonb_build_array(kb.collection_name), 8, NULL, 0,
    '仅依据检索证据回答。明确给出文档版本、工作表和单元格范围；证据没有覆盖的参数、步骤、判据、异常或安全要求必须直说“文档未提供”，不得补造。图片描述只能证明可见物体和文字，不能单独证明操作步骤或安全规则。',
    NULL, NULL,
    20, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM t_knowledge_base kb
WHERE kb.name = '铁矿检测演示库' AND kb.deleted = 0
ON CONFLICT (id) DO UPDATE SET
    kb_id = EXCLUDED.kb_id,
    intent_code = EXCLUDED.intent_code,
    name = EXCLUDED.name,
    level = EXCLUDED.level,
    parent_code = EXCLUDED.parent_code,
    description = EXCLUDED.description,
    examples = EXCLUDED.examples,
    collection_name = EXCLUDED.collection_name,
    collection_names = EXCLUDED.collection_names,
    top_k = EXCLUDED.top_k,
    mcp_tool_id = EXCLUDED.mcp_tool_id,
    kind = EXCLUDED.kind,
    prompt_snippet = EXCLUDED.prompt_snippet,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    update_by = EXCLUDED.update_by,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;
