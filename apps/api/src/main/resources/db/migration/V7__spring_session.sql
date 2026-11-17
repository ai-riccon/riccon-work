-- =============================================================================
-- V7：Spring Session JDBC 会话表（M02）
--
-- 这是第三方基础设施表，按 ADR-0009 第 4 节的全局表例外处理：没有 tenant_id，
-- 租户身份只从会话中的已认证主体（SecurityContext）派生，禁止从 Cookie 或请求头读取租户。
-- 表名与列名保持 Spring Session 官方 schema（大写），避免 Linux 大小写敏感导致运行期找不到表；
-- 不启用 Spring Session 自动建表（spring.session.jdbc.initialize-schema=never），只由 Flyway 创建。
-- 会话过期清理由 Spring Session 定时任务删除，不属于审计 append-only 范围。
-- =============================================================================

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内部主键（随机 UUID）',
    SESSION_ID            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会话 ID，登录后轮换',
    CREATION_TIME         BIGINT NOT NULL COMMENT '创建时间（epoch 毫秒）',
    LAST_ACCESS_TIME      BIGINT NOT NULL COMMENT '最近访问时间（epoch 毫秒）',
    MAX_INACTIVE_INTERVAL INT NOT NULL COMMENT '最大空闲秒数',
    EXPIRY_TIME           BIGINT NOT NULL COMMENT '过期时间（epoch 毫秒）',
    PRINCIPAL_NAME        VARCHAR(100) NULL COMMENT '主体名索引，用于按用户查会话',
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE = InnoDB
  ROW_FORMAT = DYNAMIC
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Spring Session 会话（基础设施表）';

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '父会话内部主键',
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL COMMENT '属性名，如 SPRING_SECURITY_CONTEXT',
    ATTRIBUTE_BYTES    BLOB NOT NULL COMMENT 'JDK 序列化后的属性值；服务端存储，不下发浏览器',
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB
  ROW_FORMAT = DYNAMIC
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Spring Session 属性（基础设施表）';
