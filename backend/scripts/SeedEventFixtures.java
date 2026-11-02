import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher;

class SeedEventFixtures {
    static final String PREFIX = "evt-local-fixture-v1-";
    static final String TYPE = "demo.fixture.v1";
    static final String[] STATES = {"PENDING", "SUCCESS", "FINAL_FAILED", "WAIT_RETRY", "PAUSED"};
    static final String[] NAMES = {"积分服务", "库存服务", "内置故障模拟器", "订单服务", "采购服务"};
    static final long HOLD_SECONDS = 365L * 24 * 60 * 60;

    static String field(DataInputStream input) throws Exception {
        int size = input.readInt();
        if (size < 1 || size > 8192) throw new IllegalArgumentException();
        byte[] value = input.readNBytes(size);
        if (value.length != size) throw new IllegalArgumentException();
        return new String(value, StandardCharsets.UTF_8);
    }

    static long insert(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            if (statement.executeUpdate() != 1) throw new IllegalStateException();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException();
                return keys.getLong(1);
            }
        }
    }

    static void seed(Connection connection, String masterKey) throws Exception {
        connection.setAutoCommit(false);
        try (var lock = connection.prepareStatement("SELECT GET_LOCK('hookflow_local_fixture_v1', 5)")) {
            try (var result = lock.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException();
            }
        }
        try {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM event WHERE event_type = ? OR LEFT(event_id, ?) = ?")) {
                query.setString(1, TYPE); query.setInt(2, PREFIX.length()); query.setString(3, PREFIX);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) != 0) {
                        connection.rollback();
                        System.out.println("ALREADY_EXISTS");
                        return;
                    }
                }
            }
            var cipher = new EndpointSigningSecretCipher(masterKey);
            var now = LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            long[] endpoints = new long[5];
            var secrets = new ArrayList<byte[]>();
            for (int i = 0; i < NAMES.length; i++) {
                byte[] encrypted = cipher.encrypt(cipher.generateSecret());
                secrets.add(encrypted);
                endpoints[i] = insert(connection, "INSERT INTO endpoint (name,target_type,url,timeout_ms,enabled,signing_secret_ciphertext,created_at,updated_at) VALUES (?,'SIMULATOR',NULL,5000,?,?,?,?)",
                        NAMES[i], i == 1 ? 0 : 1, encrypted, now, now);
            }
            for (int i = 0; i < 30; i++) {
                int stateIndex = i % 5;
                String state = STATES[stateIndex];
                // 暂停样本只关联停用库存端，其余样本只关联启用端。
                int[] enabled = {0, 2, 3, 4};
                int endpointIndex = stateIndex == 4 ? 1 : enabled[(i / 5 + stateIndex) % 4];
                int count = stateIndex == 1 || stateIndex == 3 ? 1 : stateIndex == 2 ? 3 : 0;
                LocalDateTime created = now.minusSeconds(i * 60L + 60);
                LocalDateTime ended = count == 0 ? null : created.plusSeconds(count == 3 ? 43 : 1).plusNanos(100_000_000);
                boolean terminal = stateIndex == 1 || stateIndex == 2;
                String id = PREFIX + String.format("%03d", i + 1);
                String body = "{\"amount\":" + (100 + i * 17) + ",\"demoFixture\":\"local-v1\",\"fictional\":true,\"orderId\":\"DEMO-" + (i + 1) + "\",\"sequence\":" + (i + 1) + "}";
                byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
                long event = insert(connection, "INSERT INTO event (event_id,event_type,payload_json,payload_fingerprint,endpoint_id,latest_round_no,current_status,created_at,terminal_at) VALUES (?,?,?,?,?,1,?,?,?)",
                        id, TYPE, body, fingerprint, endpoints[endpointIndex], state, created, terminal ? ended : null);
                LocalDateTime due = stateIndex == 0 ? now.plusSeconds(HOLD_SECONDS) : stateIndex == 3 ? ended.plusSeconds(HOLD_SECONDS) : null;
                long task = insert(connection, "INSERT INTO delivery_task (event_pk,round_no,status,attempt_count,max_attempts,retry_delay_1_s,retry_delay_2_s,next_run_at,resume_status,resume_due_at,target_type_snapshot,url_snapshot,timeout_ms_snapshot,secret_ciphertext_snapshot,created_at,updated_at,finished_at) VALUES (?,1,?,?,3,?,30,?,?,?,'SIMULATOR',NULL,5000,?,?,?,?)",
                        event, state, count, stateIndex == 3 ? HOLD_SECONDS : 10, due,
                        stateIndex == 4 ? "PENDING" : null, stateIndex == 4 ? created : null,
                        secrets.get(endpointIndex), created, terminal ? ended : now, terminal ? ended : null);
                for (int attempt = 1; attempt <= count; attempt++) {
                    LocalDateTime start = attempt == count ? ended.minusNanos(100_000_000) : created.plusSeconds(attempt == 1 ? 1 : 12);
                    LocalDateTime finish = start.plusNanos(100_000_000);
                    boolean success = stateIndex == 1;
                    insert(connection, "INSERT INTO delivery_attempt (task_id,attempt_no,result,started_at,ended_at,duration_ms,http_status,response_excerpt,error_code,error_message,request_id) VALUES (?,?,?,?,?,100,?,?,?,?,?)",
                            task, attempt, success ? "SUCCESS" : "HTTP_FAILURE", start, finish, success ? 200 : 500,
                            "本机测试夹具：虚构响应，非真实HTTP记录", success ? null : "HTTP_NON_SUCCESS",
                            success ? null : "测试夹具：模拟非成功响应", UUID.randomUUID().toString());
                }
            }
            try (var query = connection.prepareStatement("SELECT current_status,COUNT(*) FROM event WHERE event_type=? GROUP BY current_status")) {
                query.setString(1, TYPE);
                try (var rows = query.executeQuery()) {
                    int groups = 0;
                    while (rows.next()) { if (rows.getInt(2) != 6) throw new IllegalStateException(); groups++; }
                    if (groups != 5) throw new IllegalStateException();
                }
            }
            connection.commit();
            System.out.println("CREATED_30");
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            try (var release = connection.prepareStatement("SELECT RELEASE_LOCK('hookflow_local_fixture_v1')")) { release.execute(); }
        }
    }

    public static void main(String[] args) {
        try (var input = new DataInputStream(System.in)) {
            String url = field(input), user = field(input), password = field(input), master = field(input);
            if (!url.startsWith("jdbc:mysql://127.0.0.1:") || !user.equals("hookflow_dev")) throw new IllegalArgumentException();
            try (var connection = DriverManager.getConnection(url, user, password)) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                seed(connection, master);
            }
        } catch (Exception failure) {
            System.out.println("FIXTURE_FAILED");
            System.exit(1);
        }
    }
}
