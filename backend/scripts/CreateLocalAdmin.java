import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class CreateLocalAdmin {
    private static String field(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 1 || length > 8192) throw new IllegalArgumentException();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IllegalArgumentException();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        try (var input = new DataInputStream(System.in)) {
            String url = field(input);
            String databaseUser = field(input);
            String databasePassword = field(input);
            String username = field(input);
            String password = field(input);
            if (!url.startsWith("jdbc:mysql://127.0.0.1:") || username.isBlank()
                    || username.codePointCount(0, username.length()) > 64 || password.isBlank()
                    || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                System.out.println("INVALID_INPUT");
                System.exit(2);
            }
            try (var connection = DriverManager.getConnection(url, databaseUser, databasePassword)) {
                connection.setAutoCommit(false);
                try (var query = connection.prepareStatement("SELECT id FROM app_user WHERE username = ?")) {
                    query.setString(1, username);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            System.out.println("ALREADY_EXISTS");
                            return;
                        }
                    }
                }
                String hash = new BCryptPasswordEncoder().encode(password);
                try (var insert = connection.prepareStatement(
                        "INSERT INTO app_user (username,password_hash,role,created_at) VALUES (?,?,'ADMIN',?)")) {
                    insert.setString(1, username);
                    insert.setString(2, hash);
                    insert.setObject(3, LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
                    insert.executeUpdate();
                } catch (SQLIntegrityConstraintViolationException duplicate) {
                    connection.rollback();
                    System.out.println("CREATE_CONFLICT");
                    return;
                }
                connection.commit();
                System.out.println("CREATED");
            }
        } catch (Exception failure) {
            System.out.println("CREATE_FAILED");
            System.exit(1);
        }
    }
}
