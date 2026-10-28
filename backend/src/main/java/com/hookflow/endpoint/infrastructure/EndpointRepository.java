package com.hookflow.endpoint.infrastructure; // 接收端的 SQL 查询属于基础设施层。

import java.time.LocalDateTime; // 读取数据库 DATETIME(3) 字段。
import java.time.ZoneOffset; // 明确将数据库中的时间按 UTC 解释。
import java.util.List; // 返回分页查询得到的接收端列表。
import java.util.Optional; // 表示指定 ID 的接收端可能不存在。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 配置。
import org.springframework.jdbc.core.JdbcTemplate; // 使用现有 Spring JDBC 执行参数化查询。
import org.springframework.jdbc.core.RowMapper; // 将数据库行转换为内部配置对象。
import org.springframework.stereotype.Repository; // 将仓库注册为 Spring 组件。
import com.hookflow.endpoint.domain.EndpointConfiguration; // 返回不含密钥的配置对象。
import com.hookflow.endpoint.domain.EndpointTargetType; // 解析数据库中的固定目标类型。

@Profile("!skeleton") // 无数据库的骨架测试不创建此仓库。
@Repository // 供后续接收端应用服务注入。
public class EndpointRepository { // 集中管理接收端配置的 JDBC 查询。
    private static final String SELECT_COLUMNS = // 固定查询列，避免读取签名密钥密文。
            "SELECT id, name, target_type, url, timeout_ms, enabled, created_at, updated_at FROM endpoint"; // 只选择管理查询需要的字段。

    private static final RowMapper<EndpointConfiguration> ROW_MAPPER = (rs, rowNum) -> // 将一行查询结果转成内部配置。
            new EndpointConfiguration( // 按配置 record 的字段顺序填入数据。
                    rs.getLong("id"), // 读取数据库内部 ID。
                    rs.getString("name"), // 读取接收端名称。
                    EndpointTargetType.valueOf(rs.getString("target_type")), // 将受 CHECK 约束的类型转为枚举。
                    rs.getString("url"), // 模拟器的数据库 NULL 会保留为 Java null。
                    rs.getInt("timeout_ms"), // 读取毫秒单位的超时值。
                    rs.getBoolean("enabled"), // 将数据库的 0/1 转为布尔值。
                    rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), // 按 UTC 解释创建时间。
                    rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC) // 按 UTC 解释修改时间。
            ); // 单行转换结束。

    private final JdbcTemplate jdbcTemplate; // 保存 Spring 提供的数据库查询工具。

    public EndpointRepository(JdbcTemplate jdbcTemplate) { // 通过构造方法注入 JDBC 工具。
        this.jdbcTemplate = jdbcTemplate; // 保存依赖供查询方法使用。
    } // 构造方法结束。

    public Optional<EndpointConfiguration> findById(long id) { // 按内部 ID 查找一条接收端。
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id) // 使用参数绑定，不拼接用户输入。
                .stream().findFirst(); // 不存在时返回空 Optional。
    } // 详情查询结束。

    public Optional<EndpointConfiguration> findByIdForUpdate(long id) { // 在调用方已有的写事务中锁定指定接收端。
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE id = ? FOR UPDATE", ROW_MAPPER, id) // 按主键锁行，且只读取非密钥字段。
                .stream().findFirst(); // 接收端不存在时返回空结果。
    } // 接收端锁定读取结束。

    public int updateConfiguration(long id, String name, String url, int timeoutMs, LocalDateTime updatedAt) { // 接收事务服务确定的名称、URL、超时及修改时间。
        return jdbcTemplate.update( // 返回实际更新行数，供调用方检查。
                "UPDATE endpoint SET name = ?, url = ?, timeout_ms = ?, updated_at = ? WHERE id = ?", // 只更新配置，不改类型、启停状态或签名密文。
                name, url, timeoutMs, updatedAt, id // 参数顺序与五个 SQL 占位符严格对应。
        ); // 执行参数化更新，不拼接用户输入。
    } // 接收端配置更新方法结束。

    public long countAll() { // 查询未筛选的接收端总数。
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM endpoint", Long.class); // COUNT 由数据库计算，不加载全部行。
    } // 总数查询结束。

    public List<EndpointConfiguration> findPage(int size, long offset) { // 查询指定大小及偏移量的一页数据。
        return jdbcTemplate.query( // 用数据库分页，避免在内存中截取全部记录。
                SELECT_COLUMNS + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", // 创建时间相同时用 ID 保证稳定顺序。
                ROW_MAPPER, size, offset // 分页值由后续应用层校验，再作为 SQL 参数绑定。
        ); // 返回本页配置，不包含密文字段。
    } // 分页查询结束。
} // 只读接收端仓库结束。