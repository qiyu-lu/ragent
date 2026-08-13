-- Enable exactly two portable KB intents for the controlled evaluation.
-- Compatible with the frozen bcfba62 baseline and the current checkout.
-- Run only after prepare_kb.py has created both named knowledge bases.

BEGIN;

DO $$
BEGIN
    IF (SELECT count(*) FROM t_knowledge_base WHERE name = '铁矿检测评测库' AND deleted = 0) <> 1 THEN
        RAISE EXCEPTION '需要且只能有一个活动知识库：铁矿检测评测库';
    END IF;
    IF (SELECT count(*) FROM t_knowledge_base WHERE name = '钛铁矿挑战库' AND deleted = 0) <> 1 THEN
        RAISE EXCEPTION '需要且只能有一个活动知识库：钛铁矿挑战库';
    END IF;
    IF (
        SELECT count(*)
        FROM t_intent_node
        WHERE deleted = 0
          AND id NOT IN ('20260813000000000001', '20260813000000000002')
    ) <> 0 THEN
        RAISE EXCEPTION '评测库中存在非评测意图，请恢复干净数据库后再执行';
    END IF;
END $$;

INSERT INTO t_intent_node (
    id, kb_id, intent_code, name, level, parent_code, description, examples,
    collection_name, collection_names, top_k, mcp_tool_id, kind,
    prompt_snippet, prompt_template, param_prompt_template,
    sort_order, enabled, create_by, update_by, create_time, update_time, deleted
)
SELECT
    '20260813000000000001', kb.id, 'eval-iron-ore', '铁矿石检测资料', 0, NULL,
    '查询铁矿石人工检测调研流程、制样、粒度、二氧化硅检测，或GB/T 6730.10铁矿石硅含量重量法。不要用于钛铁矿精矿氧化亚铁滴定。',
    '["现场和中心实验室自动化建议是什么","粒度水筛法称取多少干样","GB/T 6730.10的试料怎样分解","铁矿石硅含量重量法的适用范围"]',
    kb.collection_name, jsonb_build_array(kb.collection_name), 10, NULL, 0,
    '只依据铁矿石评测库证据回答；遇到钛铁矿氧化亚铁问题不得套用本库参数。资料未提供的品牌、型号、周期或数值必须明确说明未提供。',
    NULL, NULL, 10, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM t_knowledge_base kb
WHERE kb.name = '铁矿检测评测库' AND kb.deleted = 0
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
    kind = EXCLUDED.kind,
    prompt_snippet = EXCLUDED.prompt_snippet,
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
    '20260813000000000002', kb.id, 'eval-titanium-ore', '钛铁矿氧化亚铁资料', 0, NULL,
    '查询YS/T 360.3钛铁矿精矿中氧化亚铁量的重铬酸钾滴定法，包括适用范围、试料、步骤、精密度和允许差。不要用于铁矿石硅含量或现场粒度检测。',
    '["YS/T 360.3测定什么","氧化亚铁滴定终点是什么颜色","钛铁矿试样称取多少","氧化亚铁允许差是多少"]',
    kb.collection_name, jsonb_build_array(kb.collection_name), 10, NULL, 0,
    '只依据钛铁矿挑战库证据回答；不得把氧化亚铁滴定参数套用到铁矿石硅含量、粒度或制样流程。资料没有保存期限时必须明确说明未规定。',
    NULL, NULL, 20, 1, 'admin', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM t_knowledge_base kb
WHERE kb.name = '钛铁矿挑战库' AND kb.deleted = 0
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
    kind = EXCLUDED.kind,
    prompt_snippet = EXCLUDED.prompt_snippet,
    sort_order = EXCLUDED.sort_order,
    enabled = EXCLUDED.enabled,
    update_by = EXCLUDED.update_by,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;

COMMIT;

SELECT id, intent_code, name, collection_name, enabled, deleted
FROM t_intent_node
WHERE id IN ('20260813000000000001', '20260813000000000002')
ORDER BY id;
