-- V14 知识库 FULLTEXT 检索（M05 B3b，DEC-0038=A）
--
-- 目的：替换 `AgentContextJdbc.search` 的 leading-wildcard LIKE（`%term%` 全表扫描），
-- 为中文/英文关键词检索建立 InnoDB FULLTEXT ngram 索引：
--   * `knowledge_chunk.content`：正文命中；
--   * `knowledge_document.title`：标题命中。
-- ngram parser 使用 MySQL 默认 token size 2，中文按二元组、英文按字符二元组索引；
-- MATCH 仍由 MySQL 做租户 + 文档状态 + ACL 过滤，OpenSearch 不参与授权（ADR-0023 §5.4/§5.5）。
--
-- 只加索引、不改列、不回填数据；历史行在索引创建后立即可检索。
--
-- 回滚 SQL（仅回滚窗口；先停新 Worker，再删索引；删除 history 14 后启动旧 Worker expected=13）：
--   ALTER TABLE knowledge_chunk DROP INDEX ft_knowledge_chunk_content, ALGORITHM=INPLACE, LOCK=NONE;
--   ALTER TABLE knowledge_document DROP INDEX ft_knowledge_document_title, ALGORITHM=INPLACE, LOCK=NONE;
--   DELETE FROM flyway_schema_history WHERE version = '14';
--
-- 锁与时间：MySQL 8.4 的 InnoDB FULLTEXT 创建要求 LOCK=SHARED（错误 1846），即允许并发读、
-- 阻塞写；实际耗时随 knowledge_chunk 行数增长，生产维护窗口与回滚演练由 M09 在客户数据量上测量
-- （不在本迁移内声明已量测）。

ALTER TABLE knowledge_chunk
    ADD FULLTEXT KEY ft_knowledge_chunk_content (content) WITH PARSER ngram,
    ALGORITHM = INPLACE,
    LOCK = SHARED;

ALTER TABLE knowledge_document
    ADD FULLTEXT KEY ft_knowledge_document_title (title) WITH PARSER ngram,
    ALGORITHM = INPLACE,
    LOCK = SHARED;
