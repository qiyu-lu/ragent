-- Restore the no-intent arm without touching any non-evaluation rows.
BEGIN;
DELETE FROM t_intent_node
WHERE id IN ('20260813000000000001', '20260813000000000002');
COMMIT;

SELECT count(*) AS remaining_eval_intents
FROM t_intent_node
WHERE id IN ('20260813000000000001', '20260813000000000002')
  AND deleted = 0;
