-- P1: stop routing ordinary chat to the retired task simulation MCP tool.
-- Apply manually to existing environments, then invalidate the application Redis intent cache.
-- Historical task/sample/station/execution/robot tables and their data are retained.
BEGIN;

UPDATE t_intent_node
SET enabled = 0,
    update_time = CURRENT_TIMESTAMP
WHERE mcp_tool_id = 'iron_ore_simulate_task'
  AND enabled <> 0;

COMMIT;
