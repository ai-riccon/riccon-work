package com.riccon.api.workflow.template.persistence;

import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.persistence.SqlTimestamps;
import com.riccon.platform.domain.workflow.WorkflowTemplateVisibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Component;

/**
 * 全局内置模板目录只读 SQL。
 *
 * <p>{@code workflow_template} 是 ADR-0012 §4 全局表例外清单的 M06 扩展（ADR-0024 §3.3.7），不带
 * {@code tenant_id}，因此不使用 {@link com.riccon.api.persistence.TenantJdbc}；本类只读取
 * {@code visibility=BUILT_IN} 且启用（{@code ACTIVE}）的模板行，不提供任何写路径。
 *
 * <p>名称/场景过滤使用参数绑定的 {@code LIKE}，通配符按 {@code !} 转义，禁止把用户输入拼进 SQL。
 * 排序固定为最近更新时间倒序、ID 升序作为稳定次序；契约未声明排序参数，列表接口不接受额外排序键。
 */
@Component
public class WorkflowTemplateJdbc {

    /** 列表与详情共用的查询列；不读取图 JSON。 */
    private static final String ROW_COLUMNS = "id, name, scenario, description, visibility, version, updated_at";

    /** 内置目录基础条件；参数由调用方绑定。 */
    private static final String BASE_FILTER = " WHERE visibility = :visibility AND status = :status";

    /** 模板状态取值；V15 表只有 ACTIVE/DISABLED，M06 没有模板状态枚举。 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** {@code LIKE} 转义字符；不使用反斜杠，避免 Java/MySQL 双重转义。 */
    private static final String LIKE_ESCAPE = "!";

    /** 排序子句；稳定次序避免翻页时同一行重复或遗漏。 */
    private static final String ORDER_BY = " ORDER BY updated_at DESC, id ASC";

    /** JDBC 客户端；全局表无租户守卫。 */
    private final JdbcClient jdbcClient;

    /**
     * 创建模板目录只读 SQL。
     *
     * @param jdbcClient Spring JDBC 客户端
     */
    public WorkflowTemplateJdbc(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    /**
     * 分页查询启用的内置模板。
     *
     * @param scenario 可选场景过滤（包含匹配）；为空表示不过滤
     * @param name 可选名称过滤（包含匹配）；为空表示不过滤
     * @param limit 每页大小
     * @param offset 偏移量
     * @return 模板行
     */
    public List<WorkflowTemplateRow> list(String scenario, String name, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(ROW_COLUMNS).append(" FROM workflow_template");
        appendFilters(sql, scenario, name);
        sql.append(ORDER_BY).append(" LIMIT :limit OFFSET :offset");
        return bindFilter(jdbcClient.sql(sql.toString()), scenario, name)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapRow)
                .list();
    }

    /**
     * 统计启用的内置模板。
     *
     * @param scenario 可选场景过滤（包含匹配）；为空表示不过滤
     * @param name 可选名称过滤（包含匹配）；为空表示不过滤
     * @return 总数
     */
    public long count(String scenario, String name) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM workflow_template");
        appendFilters(sql, scenario, name);
        return bindFilter(jdbcClient.sql(sql.toString()), scenario, name)
                .query(Long.class)
                .single();
    }

    /**
     * 按 ID 读取启用的内置模板。
     *
     * <p>复制入口先用本方法确认模板存在：契约对未知模板声明 404，而 T02 的
     * {@code createFromTemplate} 面向 {@code POST /workflows} 返回 400；两者共享同一读取口径。
     *
     * @param templateId 模板 ID
     * @return 模板行
     */
    public Optional<WorkflowTemplateRow> findActive(UUID templateId) {
        return jdbcClient
                .sql("SELECT " + ROW_COLUMNS + " FROM workflow_template"
                        + " WHERE id = :templateId AND visibility = :visibility AND status = :status")
                .param("templateId", BinaryUuids.toBytes(templateId))
                .param("visibility", WorkflowTemplateVisibility.BUILT_IN.name())
                .param("status", STATUS_ACTIVE)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 追加内置目录基础条件与可选过滤片段。
     *
     * <p>SQL 只拼接固定片段，用户输入全部通过命名参数绑定；通配符在 {@link #likePattern} 中转义。
     *
     * @param sql SQL 构建器；方法会追加条件
     * @param scenario 可选场景过滤
     * @param name 可选名称过滤
     */
    private static void appendFilters(StringBuilder sql, String scenario, String name) {
        sql.append(BASE_FILTER);
        if (hasText(scenario)) {
            sql.append(" AND scenario LIKE :scenario ESCAPE '")
                    .append(LIKE_ESCAPE)
                    .append("'");
        }
        if (hasText(name)) {
            sql.append(" AND name LIKE :name ESCAPE '").append(LIKE_ESCAPE).append("'");
        }
    }

    /**
     * 绑定基础参数与可选过滤参数。
     *
     * @param statement 已包含完整 SQL 的语句
     * @param scenario 可选场景过滤
     * @param name 可选名称过滤
     * @return 已绑定参数的语句
     */
    private static StatementSpec bindFilter(StatementSpec statement, String scenario, String name) {
        StatementSpec bound = statement
                .param("visibility", WorkflowTemplateVisibility.BUILT_IN.name())
                .param("status", STATUS_ACTIVE);
        if (hasText(scenario)) {
            bound = bound.param("scenario", likePattern(scenario));
        }
        if (hasText(name)) {
            bound = bound.param("name", likePattern(name));
        }
        return bound;
    }

    /**
     * 判断过滤值是否有内容。
     *
     * @param value 过滤值
     * @return 有内容返回 {@code true}
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 构造包含匹配的 {@code LIKE} 模式并转义通配符。
     *
     * @param value 用户输入
     * @return 形如 {@code %value%} 的模式
     */
    private static String likePattern(String value) {
        String escaped = value.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped + "%";
    }

    /**
     * 映射模板行。
     *
     * @param rs 结果集
     * @param rowNum 行号
     * @return 模板行
     * @throws SQLException 读取失败
     */
    private WorkflowTemplateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowTemplateRow(
                BinaryUuids.fromBytes(rs.getBytes("id")),
                rs.getString("name"),
                rs.getString("scenario"),
                rs.getString("description"),
                WorkflowTemplateVisibility.valueOf(rs.getString("visibility")),
                rs.getInt("version"),
                SqlTimestamps.toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }
}
