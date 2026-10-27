package com.hookflow; // 放在后端应用的根包下。

import static org.junit.jupiter.api.Assertions.assertEquals; // 比较迁移次数和数据库查询结果。
import static org.junit.jupiter.api.Assertions.assertTrue; // 确认数据库查询返回了结果行。
import java.sql.Connection; // 表示连接到容器内真实 MySQL 的 JDBC 连接。
import java.sql.DriverManager; // 使用容器提供的地址和凭据创建 JDBC 连接。
import java.sql.ResultSet; // 读取真实数据库的查询结果。
import java.sql.SQLException; // 声明 JDBC 操作可能产生的数据库异常。
import java.sql.Statement; // 执行只读的数据库验证查询。
import org.flywaydb.core.Flyway; // 使用与应用相同的 Flyway 迁移工具。
import org.junit.jupiter.api.Test; // 标记 JUnit 测试方法。
import org.testcontainers.junit.jupiter.Container; // 让测试扩展管理容器生命周期。
import org.testcontainers.junit.jupiter.Testcontainers; // 启用 Testcontainers 的 JUnit 扩展。
import org.testcontainers.mysql.MySQLContainer; // 使用 Testcontainers 2.x 的 MySQL 容器类。

@Testcontainers // 在测试执行前启动容器，测试结束后关闭容器。
class DatabaseMigrationTests { // 只验证 P1-05 的真实迁移基础设施。
    @Container // 这个静态容器供本类测试使用。
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13"); // 与 P1-03 验证过的 MySQL 版本一致。

    @Test // 让 JUnit 执行真实数据库迁移验证。
    void migratesElevenBusinessTables() throws SQLException { // JDBC 查询失败时让测试直接失败。
        Flyway flyway = Flyway.configure() // 创建本次测试专用的 Flyway 配置。
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()) // 连接一次性容器，不连接本地已有数据库。
                .locations("classpath:db/migration") // 读取项目现有的 V1 迁移文件。
                .load(); // 根据以上配置创建 Flyway 实例。
        assertEquals(1, flyway.migrate().migrationsExecuted); // 空容器应实际执行一次 V1 迁移。

        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); // 打开真实 JDBC 连接。
             Statement statement = connection.createStatement()) { // 创建只读验证所需的 SQL 语句对象。
            try (ResultSet tables = statement.executeQuery( // 查询当前数据库中的业务表数量。
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                            + "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'")) { // 排除 Flyway 自身的历史表。
                assertTrue(tables.next()); // COUNT 查询必须返回一行。
                assertEquals(11, tables.getInt(1)); // V1 必须创建约定的 11 张业务表。
            } // 关闭业务表查询结果。

            try (ResultSet history = statement.executeQuery( // 查询 Flyway 写入的实际迁移历史。
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1")) { // 只计成功的 V1 记录。
                assertTrue(history.next()); // COUNT 查询必须返回一行。
                assertEquals(1, history.getInt(1)); // 数据库中必须存在一条成功的 V1 记录。
            } // 关闭迁移历史查询结果。
        } // 自动关闭 SQL 语句和数据库连接。
    } // 测试方法结束。
} // 测试类结束。