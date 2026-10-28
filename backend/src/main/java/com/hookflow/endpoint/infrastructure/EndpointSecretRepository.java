package com.hookflow.endpoint.infrastructure; // 接收端密文的数据库操作留在基础设施层。

import java.sql.PreparedStatement; // 为插入语句绑定字段。
import java.sql.Statement; // 请求数据库返回新插入行的主键。
import java.time.LocalDateTime; // 对应数据库中的 UTC DATETIME(3) 字段。
import java.util.Optional; // 表示指定接收端可能不存在。
import org.springframework.context.annotation.Profile; // 排除不连接数据库的 skeleton 模式。
import org.springframework.jdbc.core.JdbcTemplate; // 复用项目已有的 JDBC 访问方式。
import org.springframework.jdbc.support.GeneratedKeyHolder; // 接收数据库生成的接收端 ID。
import org.springframework.stereotype.Repository; // 将仓库注册为正式应用组件。

@Profile("!skeleton") // 骨架测试不创建依赖数据库的仓库。
@Repository // 供接收端内部应用服务注入。
public class EndpointSecretRepository { // 将密文访问与现有不读取密文的管理查询分开。
    private final JdbcTemplate jdbcTemplate; // 保存数据库访问工具。

    public EndpointSecretRepository(JdbcTemplate jdbcTemplate) { // 通过构造方法接收现有 JDBC 依赖。
        this.jdbcTemplate = jdbcTemplate; // 供插入和快照读取使用。
    } // 仓库构造结束。

    public long insertSimulator(String name, int timeoutMs, byte[] ciphertext, LocalDateTime now) { // 只为内部模拟器目标插入加密后的密钥。
        GeneratedKeyHolder keys = new GeneratedKeyHolder(); // 保存 MySQL 生成的接收端 ID。
        int inserted = jdbcTemplate.update(connection -> { // 使用参数化 SQL，避免拼接名称或密文。
            PreparedStatement statement = connection.prepareStatement( // 建立会返回新主键的插入语句。
                    "INSERT INTO endpoint (name, target_type, url, timeout_ms, enabled, signing_secret_ciphertext, created_at, updated_at) "
                            + "VALUES (?, 'SIMULATOR', NULL, ?, 1, ?, ?, ?)", // 固定模拟器类型、空 URL 和初始启用状态。
                    Statement.RETURN_GENERATED_KEYS // 要求驱动返回本次新建行的 ID。
            ); // SQL 预编译完成。
            statement.setString(1, name); // 绑定已由应用服务检查的名称。
            statement.setInt(2, timeoutMs); // 绑定已由应用服务检查的超时毫秒数。
            statement.setBytes(3, ciphertext); // 只绑定加密结果，绝不把明文写入数据库。
            statement.setObject(4, now); // 写入本次事务的 UTC 创建时间。
            statement.setObject(5, now); // 初始修改时间与创建时间相同。
            return statement; // 将预编译语句交给 JdbcTemplate 执行。
        }, keys); // 同时取得插入数量和数据库主键。
        if (inserted != 1 || keys.getKey() == null) { // 插入或主键返回异常时不能伪称成功。
            throw new IllegalStateException("接收端密文存储失败"); // 让调用方的事务整体回滚。
        } // 插入结果检查结束。
        return keys.getKey().longValue(); // 返回新建接收端的数据库内部 ID。
    } // 内部模拟器接收端插入结束。

    public Optional<byte[]> findCiphertextForSnapshot(long endpointId) { // 只为应用层快照边界读取指定接收端的密文。
        return jdbcTemplate.query( // 不查询或返回签名密钥明文。
                "SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?", // 按主键读取已存储的二进制密文。
                (rs, rowNum) -> rs.getBytes("signing_secret_ciphertext"), // 保留数据库返回的原始密文字节。
                endpointId // 使用参数绑定而非拼接接收端 ID。
        ).stream().findFirst(); // 不存在时返回空结果，由应用服务处理。
    } // 密文快照读取结束。
} // 接收端密文仓库结束。