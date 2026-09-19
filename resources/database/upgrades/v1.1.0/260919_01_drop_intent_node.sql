-- The intent tree is retired: chat retrieval searches every active knowledge base the user can read,
-- so the node table has no reader. Apply after 260918_03_embedding_cache.sql; repeatable.
-- Clear the Redis key ragent:intent:tree by hand once; nothing writes it any more.
DROP TABLE IF EXISTS t_intent_node;
