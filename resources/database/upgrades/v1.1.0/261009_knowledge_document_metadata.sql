-- knowledge-quality 阶段 2：文档元数据（标准号、发布年、代替、检测对象、组分、方法）与解析审计。
-- 增量、可重复执行；只加一列，不回填存量文档：重新分块时由入库流程写入，未写入时检索与展示按无元数据处理。
ALTER TABLE t_knowledge_document ADD COLUMN IF NOT EXISTS doc_metadata JSONB;
COMMENT ON COLUMN t_knowledge_document.doc_metadata IS '文档元数据：标准号、发布年、代替、检测对象、组分、方法，及解析审计与归一化摘要';
