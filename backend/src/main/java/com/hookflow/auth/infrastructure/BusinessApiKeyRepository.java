package com.hookflow.auth.infrastructure; // 将业务 Key 的数据库操作放在认证模块的基础设施层。

import java.time.LocalDateTime; // 对应数据库中按 UTC 保存的创建和撤销时间。
import org.springframework.context.annotation.Profile; // 使此组件不进入无数据库的 skeleton 测试配置。
import org.springframework.jdbc.core.JdbcTemplate; // 使用项目现有的 Spring JDBC 执行参数化 SQL。
import org.springframework.stereotype.Repository; // 将数据库访问类注册为 Spring 组件。

@Profile("!skeleton") // 只有连接真实数据库的配置才创建此仓库。
@Repository // 允许后续轮换服务通过构造器注入此仓库。
public class BusinessApiKeyRepository { // 集中管理业务 Key 的锁定、撤销、插入和校验查询。
    private final JdbcTemplate jdbcTemplate; // 保存执行 SQL 所需的 JDBC 模板。

    public BusinessApiKeyRepository(JdbcTemplate jdbcTemplate) { // 由 Spring 注入 JDBC 模板。
        this.jdbcTemplate = jdbcTemplate; // 保存依赖，供下面的方法共用。
    } // 构造方法结束。

    public void lockRotationGuard() { // 必须在后续轮换服务开启的事务内部调用。
        var rows = jdbcTemplate.query( // 使用锁定读，让并发轮换等待同一条管理员记录。
                "SELECT id FROM app_user WHERE role = 'ADMIN' ORDER BY id LIMIT 1 FOR UPDATE", // 固定选择 ID 最小的管理员；即使 api_key 为空也有锁定目标。
                (rs, rowNum) -> rs.getLong("id") // 只读取内部 ID，不接触管理员密码。
        ); // 查询完成后，行锁由调用方的数据库事务持有至提交或回滚。
        if (rows.isEmpty()) { // 正式启动应已初始化管理员；没有管理员时不能安全轮换。
            throw new IllegalStateException("业务 Key 轮换缺少管理员账户"); // 不输出任何凭据。
        } // 管理员记录检查结束。
    } // 轮换互斥锁获取结束。

    public int revokeActive(LocalDateTime revokedAt) { // 撤销当前所有尚未撤销的业务 Key。
        return jdbcTemplate.update( // 返回实际更新行数，便于后续测试核对。
                "UPDATE api_key SET revoked_at = ? WHERE revoked_at IS NULL", // 只修改有效记录，保留历史记录。
                revokedAt // 使用调用方统一 UTC 时钟提供的时间。
        ); // 撤销操作结束。
    } // 有效 Key 撤销方法结束。

    public void create(byte[] keyHash, LocalDateTime createdAt) { // 插入新 Key 的摘要和创建时间。
        jdbcTemplate.update( // SQL 参数绑定，不把 Key 明文写入数据库。
                "INSERT INTO api_key (key_hash, created_at, revoked_at) VALUES (?, ?, NULL)", // 新记录初始为有效状态。
                keyHash, createdAt // 只传入 SHA-256 摘要及 UTC 时间。
        ); // 新记录插入结束。
    } // 新 Key 保存方法结束。

    public boolean isActive(byte[] keyHash) { // 按摘要判断业务 Key 当前是否有效。
        Long count = jdbcTemplate.queryForObject( // 查询匹配且未撤销的记录数量。
                "SELECT COUNT(*) FROM api_key WHERE key_hash = ? AND revoked_at IS NULL", // 不读取或返回 Key 明文。
                Long.class, keyHash // 使用固定长度摘要作为查询参数。
        ); // 数据库查询结束。
        return count != null && count == 1; // 哈希有唯一约束，只有恰好一条有效记录才通过。
    } // 业务 Key 有效性查询结束。
} // 数据库访问类结束。