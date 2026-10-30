package com.hookflow;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DatabaseConstraintIntegrationTests {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    Connection connection;

    @BeforeAll static void migrate() {
        var flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertTrue(flyway.validateWithResult().validationSuccessful);
    }

    @BeforeEach void schemaFixtures() throws SQLException {
        connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        connection.setAutoCommit(false);
        // 这些是约束探针，不用 SQL 夹具冒充正式创建、领取或成功审计。
        execute("INSERT INTO app_user(id,username,password_hash,role,created_at) VALUES(1,'schema-probe','fixture-hash','ADMIN','2026-10-01')");
        execute("INSERT INTO admin_session(id,user_id,token_hash,expires_at) VALUES(1,1,X'01','2026-10-02')");
        execute("INSERT INTO api_key(id,key_hash,created_at) VALUES(1,X'01','2026-10-01')");
        execute("INSERT INTO endpoint(id,name,target_type,url,timeout_ms,signing_secret_ciphertext,created_at,updated_at) VALUES(1,'schema-probe','SIMULATOR',NULL,5000,X'01','2026-10-01','2026-10-01')");
        execute("INSERT INTO event(id,event_id,event_type,payload_json,payload_fingerprint,endpoint_id,latest_round_no,current_status,created_at) VALUES(1,'Case-é','schema','{}',UNHEX(REPEAT('00',32)),1,1,'PENDING','2026-10-01')");
        execute("INSERT INTO delivery_task(id,event_pk,round_no,status,max_attempts,retry_delay_1_s,retry_delay_2_s,next_run_at,target_type_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,created_at,updated_at) VALUES(1,1,1,'PENDING',3,10,30,'2026-10-01','SIMULATOR',5000,X'01','2026-10-01','2026-10-01')");
        execute("INSERT INTO delivery_attempt(id,task_id,attempt_no,result,started_at,request_id) VALUES(1,1,1,'STARTED','2026-10-01','schema-probe')");
        execute("INSERT INTO audit_log(id,actor_user_id,action,object_type,object_id,occurred_at,request_id) VALUES(1,1,'ENDPOINT_CREATED','ENDPOINT','1','2026-10-01','schema-probe')");
        execute("INSERT INTO simulator_config(id,mode,updated_at) VALUES(1,'ALWAYS_200','2026-10-01')");
        execute("INSERT INTO simulator_event_state(event_id) VALUES('Case-é')");
        execute("INSERT INTO simulator_request_log(id,received_at,signature_valid) VALUES(1,'2026-10-01',0)");
    }

    @AfterEach void rollbackAndClose() throws SQLException {
        if (connection != null) try { connection.rollback(); } finally { connection.close(); }
    }

    @Test void foreignKeysRejectOrphansAndParentDeletesWithoutCascades() throws SQLException {
        for (String sql : List.of("UPDATE admin_session SET user_id=999 WHERE id=1",
                "UPDATE event SET endpoint_id=999 WHERE id=1", "UPDATE delivery_task SET event_pk=999 WHERE id=1",
                "UPDATE delivery_attempt SET task_id=999 WHERE id=1", "UPDATE audit_log SET actor_user_id=999 WHERE id=1")) rejected(sql, 1452, null);
        for (String table : List.of("app_user", "endpoint", "event", "delivery_task")) rejected("DELETE FROM " + table + " WHERE id=1", 1451, null);
        for (String table : List.of("app_user", "admin_session", "endpoint", "event", "delivery_task", "delivery_attempt", "audit_log"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM " + table));
    }

    @Test void uniqueKeysRejectDuplicatesAndEventIdentityUsesBinaryNoPadComparison() throws SQLException {
        for (String table : List.of("app_user", "admin_session", "api_key", "event", "delivery_task", "delivery_attempt")) {
            String columns = switch (table) {
                case "app_user" -> "username,password_hash,role,created_at";
                case "admin_session" -> "user_id,token_hash,expires_at";
                case "api_key" -> "key_hash,created_at";
                case "event" -> "event_id,event_type,payload_json,payload_fingerprint,endpoint_id,latest_round_no,current_status,created_at";
                case "delivery_task" -> "event_pk,round_no,status,max_attempts,retry_delay_1_s,retry_delay_2_s,next_run_at,target_type_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,created_at,updated_at";
                default -> "task_id,attempt_no,result,started_at,request_id";
            };
            rejected("INSERT INTO " + table + "(" + columns + ") SELECT " + columns + " FROM " + table + " WHERE id=1", 1062, null);
            assertEquals(1, scalar("SELECT COUNT(*) FROM " + table));
        }
        for (String identity : List.of("case-é", "Case-é", "Case-é ")) {
            try (var statement = connection.prepareStatement("INSERT INTO event(event_id,event_type,payload_json,payload_fingerprint,endpoint_id,latest_round_no,current_status,created_at) SELECT ?,event_type,payload_json,payload_fingerprint,endpoint_id,latest_round_no,current_status,created_at FROM event WHERE id=1")) {
                statement.setQueryTimeout(3); statement.setString(1, identity); assertEquals(1, statement.executeUpdate());
            }
        }
        assertEquals(4, scalar("SELECT COUNT(*) FROM event"));
    }

    @Test void endpointAndEventChecksRejectInvalidTargetsJsonAndTerminalFields() throws SQLException {
        check("endpoint", "target_type='external'", "ck_endpoint_target_type");
        check("endpoint", "url='https://example.com'", "ck_endpoint_url_by_type");
        for (int timeout : List.of(0, 15001)) check("endpoint", "timeout_ms=" + timeout, "ck_endpoint_timeout_ms");
        check("endpoint", "enabled=2", "ck_endpoint_enabled");
        check("event", "payload_json='not-json'", "ck_event_payload_json");
        check("event", "latest_round_no=0", "ck_event_latest_round_no");
        check("event", "current_status='pending'", "ck_event_current_status");
        check("event", "current_status='SUCCESS'", "ck_event_terminal_at");
        check("event", "terminal_at='2026-10-01'", "ck_event_terminal_at");
        execute("UPDATE endpoint SET timeout_ms=1 WHERE id=1"); execute("UPDATE endpoint SET timeout_ms=15000 WHERE id=1");
        execute("UPDATE event SET current_status='SUCCESS',terminal_at='2026-10-01' WHERE id=1");
    }

    @Test void taskChecksEnforceAttemptPolicyPauseClaimSnapshotAndFinishFields() throws SQLException {
        check("delivery_task", "round_no=0", "ck_delivery_task_round");
        check("delivery_task", "status='pending'", "ck_delivery_task_status");
        for (int max : List.of(0, 4)) check("delivery_task", "max_attempts=" + max, "ck_delivery_task_max_attempts");
        for (int count : List.of(-1, 4)) check("delivery_task", "attempt_count=" + count, "ck_delivery_task_attempt_count");
        check("delivery_task", "retry_delay_1_s=0", "ck_delivery_task_retry_delays");
        check("delivery_task", "retry_delay_2_s=-1", "ck_delivery_task_retry_delays");
        check("delivery_task", "next_run_at=NULL", "ck_delivery_task_next_run");
        check("delivery_task", "status='PAUSED'", "ck_delivery_task_resume");
        check("delivery_task", "resume_status='PENDING',resume_due_at='2026-10-01'", "ck_delivery_task_resume");
        check("delivery_task", "status='DELIVERING'", "ck_delivery_task_claim");
        check("delivery_task", "target_type_snapshot='OTHER'", "ck_delivery_task_target_type");
        check("delivery_task", "url_snapshot='https://example.com'", "ck_delivery_task_target_url");
        check("delivery_task", "timeout_ms_snapshot=0", "ck_delivery_task_timeout");
        check("delivery_task", "status='SUCCESS'", "ck_delivery_task_finished");
        check("delivery_task", "finished_at='2026-10-01'", "ck_delivery_task_finished");
        execute("UPDATE delivery_task SET status='PAUSED',resume_status='WAIT_RETRY',resume_due_at='2026-10-01' WHERE id=1");
    }

    @Test void attemptAndSimulatorChecksEnforceNullTimesUtf8ExcerptAndModeBounds() throws SQLException {
        check("delivery_attempt", "attempt_no=4", "ck_delivery_attempt_no");
        check("delivery_attempt", "result='unknown',ended_at='2026-10-01',duration_ms=0", "ck_delivery_attempt_result");
        check("delivery_attempt", "result='UNKNOWN'", "ck_delivery_attempt_end_fields");
        check("delivery_attempt", "ended_at='2026-10-01'", "ck_delivery_attempt_end_fields");
        check("delivery_attempt", "result='UNKNOWN',ended_at='2026-09-30',duration_ms=0", "ck_delivery_attempt_time_order");
        check("delivery_attempt", "result='UNKNOWN',ended_at='2026-10-01',duration_ms=-1", "ck_delivery_attempt_duration");
        for (int status : List.of(99, 600)) check("delivery_attempt", "http_status=" + status, "ck_delivery_attempt_http_status");
        execute("UPDATE delivery_attempt SET response_excerpt=CONCAT(REPEAT('中',1365),'a') WHERE id=1");
        assertEquals(4096, scalar("SELECT OCTET_LENGTH(response_excerpt) FROM delivery_attempt WHERE id=1"));
        check("delivery_attempt", "response_excerpt=CONCAT(REPEAT('中',1365),'ab')", "ck_delivery_attempt_excerpt");
        check("simulator_config", "id=2", "chk_simulator_config_singleton");
        check("simulator_config", "mode='always_200'", "chk_simulator_config_mode");
        check("simulator_config", "fail_first_n=-1", "chk_simulator_config_fail_count");
        check("simulator_config", "delay_ms=-1", "chk_simulator_config_delay");
        check("simulator_config", "fail_first_n=1", "chk_simulator_config_fail_mode");
        check("simulator_config", "mode='PROCESSED_THEN_DELAY'", "chk_simulator_config_delay_mode");
        rejected("UPDATE simulator_event_state SET received_count=-1", 3819, "chk_simulator_event_state_received_count");
        check("simulator_request_log", "signature_valid=2", "chk_simulator_request_log_signature");
        check("simulator_request_log", "planned_status=600", "chk_simulator_request_log_status");
        check("simulator_request_log", "duplicate_processed=2", "chk_simulator_request_log_duplicate");
        check("simulator_request_log", "config_mode='OTHER'", "chk_simulator_request_log_mode");
    }

    void check(String table, String assignment, String constraint) throws SQLException { rejected("UPDATE " + table + " SET " + assignment + " WHERE id=1", 3819, constraint); }
    void rejected(String sql, int code, String constraint) throws SQLException {
        var savepoint = connection.setSavepoint();
        try {
            SQLException failure = assertThrows(SQLException.class, () -> execute(sql));
            assertEquals(code, failure.getErrorCode(), "拒绝必须来自预期的真实MySQL约束");
            if (constraint != null) assertTrue(failure.getMessage().contains(constraint), constraint);
        } finally { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
    }
    void execute(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.setQueryTimeout(3); statement.executeUpdate(sql); } }
    int scalar(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.setQueryTimeout(3); try (var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getInt(1); } } }
}
