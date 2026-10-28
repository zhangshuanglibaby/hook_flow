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

    public long insertSimulator(String name, int timeoutMs, byte[] ciphertext, LocalDateTime now) { // 保留现有内部调用的默认启用行为。
        return insertSimulator(name, timeoutMs, true, ciphertext, now); // 旧调用一律按 enabled=true 创建。
    } // 默认启用的模拟器插入结束。

    public long insertSimulator(String name, int timeoutMs, boolean enabled, byte[] ciphertext, LocalDateTime now) { // 支持管理员创建时指定启用状态。
        GeneratedKeyHolder keys = new GeneratedKeyHolder(); // 接收数据库生成的接收端主键。
        int inserted = jdbcTemplate.update(connection -> { // 在调用方事务中执行参数化插入。
            PreparedStatement statement = connection.prepareStatement( // 建立可返回新主键的预编译语句。
                    "INSERT INTO endpoint (name, target_type, url, timeout_ms, enabled, signing_secret_ciphertext, created_at, updated_at) "
                            + "VALUES (?, 'SIMULATOR', NULL, ?, ?, ?, ?, ?)", // 固定内部类型和空 URL，不接收用户提供的地址。
                    Statement.RETURN_GENERATED_KEYS // 要求 JDBC 返回新行主键。
            ); // 预编译语句创建结束。
            statement.setString(1, name); // 绑定已校验的名称。
            statement.setInt(2, timeoutMs); // 绑定已校验的超时值。
            statement.setBoolean(3, enabled); // 保存请求指定或默认的启用状态。
            statement.setBytes(4, ciphertext); // 数据库只接收加密后的签名密钥。
            statement.setObject(5, now); // 写入统一的 UTC 创建时间。
            statement.setObject(6, now); // 新建时修改时间与创建时间相同。
            return statement; // 交给 JdbcTemplate 执行。
        }, keys); // 同时取得受影响行数和生成的主键。
        if (inserted != 1 || keys.getKey() == null) { // 插入结果异常时不能返回成功。
            throw new IllegalStateException("接收端密文存储失败"); // 抛出异常以回滚创建事务。
        } // 插入结果检查结束。
        return keys.getKey().longValue(); // 仅返回数据库内部 ID。
    } // 可指定启用状态的模拟器插入结束。


    public long insertExternal(String name, String url, int timeoutMs, boolean enabled, byte[] ciphertext, LocalDateTime now) { // 仅供后续已完成 URL 校验的创建服务调用，保存外部接收端的密文。
        GeneratedKeyHolder keys = new GeneratedKeyHolder(); // 接收 MySQL 为新接收端生成的主键。
        int inserted = jdbcTemplate.update(connection -> { // 在调用方事务内执行参数化插入。
            PreparedStatement statement = connection.prepareStatement( // 创建可返回数据库主键的预编译语句。
                    "INSERT INTO endpoint (name, target_type, url, timeout_ms, enabled, signing_secret_ciphertext, created_at, updated_at) " // 明确列出所有创建字段。
                            + "VALUES (?, 'EXTERNAL', ?, ?, ?, ?, ?, ?)", // 固定目标类型，其他值通过参数绑定。
                    Statement.RETURN_GENERATED_KEYS // 要求 JDBC 返回新行的主键。
            ); // 预编译语句创建结束。
            statement.setString(1, name); // 绑定已校验的接收端名称。
            statement.setString(2, url); // 绑定已通过外部 URL 安全校验的地址。
            statement.setInt(3, timeoutMs); // 绑定合法的毫秒超时值。
            statement.setBoolean(4, enabled); // 保存创建请求指定或默认的启用状态。
            statement.setBytes(5, ciphertext); // 只保存加密后的签名密钥，不保存明文。
            statement.setObject(6, now); // 使用本次事务统一的 UTC 创建时间。
            statement.setObject(7, now); // 新建时修改时间与创建时间相同。
            return statement; // 将绑定完成的语句交给 JdbcTemplate。
        }, keys); // 执行插入并接收生成的主键。
        if (inserted != 1 || keys.getKey() == null) { // 插入数量或主键异常时禁止返回成功。
            throw new IllegalStateException("外部接收端密文存储失败"); // 抛出异常，由后续创建事务整体回滚。
        } // 插入结果检查结束。
        return keys.getKey().longValue(); // 仅返回内部数字 ID，不返回密钥。
    } // 外部接收端密文入库方法结束。

    public Optional<byte[]> findCiphertextForSnapshot(long endpointId) { // 只为应用层快照边界读取指定接收端的密文。
        return jdbcTemplate.query( // 不查询或返回签名密钥明文。
                "SELECT signing_secret_ciphertext FROM endpoint WHERE id = ?", // 按主键读取已存储的二进制密文。
                (rs, rowNum) -> rs.getBytes("signing_secret_ciphertext"), // 保留数据库返回的原始密文字节。
                endpointId // 使用参数绑定而非拼接接收端 ID。
        ).stream().findFirst(); // 不存在时返回空结果，由应用服务处理。
    } // 密文快照读取结束。
} // 接收端密文仓库结束。