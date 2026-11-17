/**
 * M02 领域词汇。
 *
 * <p>本模块只放跨部署物共享的枚举与常量，不放 JPA 实体、Repository、Spring 注解或业务逻辑。数据模型的权威定义在
 * Flyway 迁移中；这里的值必须与迁移中的 CHECK 约束、REST/事件契约保持一致。若需要新增共享类型，必须先确认
 * api 与 worker 都会使用，避免把单一部署物的实现细节下沉。
 */
package com.riccon.platform.domain;
