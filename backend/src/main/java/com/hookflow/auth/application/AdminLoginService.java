package com.hookflow.auth.application; // 登录流程属于认证模块应用层。

import java.nio.charset.StandardCharsets; // 明确 Token 哈希所用的字符编码。
import java.security.MessageDigest; // 使用 SHA-256 计算 Token 哈希。
import java.security.NoSuchAlgorithmException; // 处理运行环境缺少 SHA-256 的异常。
import java.security.SecureRandom; // 生成不可预测的随机 Token 字节。
import java.time.Clock; // 使用项目统一的可控 UTC 时钟。
import java.time.Duration; // 定义短期 Token 的有效时长。
import java.time.Instant; // 表示响应中的绝对到期时间。
import java.time.LocalDateTime; // 对应数据库的 UTC DATETIME(3)。
import java.time.ZoneOffset; // 明确使用 UTC 转换数据库时间。
import java.time.temporal.ChronoUnit; // 将当前时间截取到数据库支持的毫秒精度。
import java.util.Base64; // 将随机字节编码为可放入请求头的文本。
import java.util.Optional; // 表示 Token 可能无效或已过期。
import java.util.regex.Pattern; // 限制待校验 Token 的格式和长度。
import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 测试。
import org.springframework.security.authentication.BadCredentialsException; // 表示用户名或密码错误。
import org.springframework.security.crypto.password.PasswordEncoder; // 校验数据库中的密码摘要。
import org.springframework.stereotype.Service; // 注册登录应用服务。
import com.hookflow.auth.infrastructure.AdminSessionRepository; // 保存并查询会话哈希。
import com.hookflow.auth.infrastructure.AdminUserRepository; // 按用户名查询管理员账户。

@Profile("!skeleton") // 只有连接数据库的配置才启用登录服务。
@Service // 供后续登录 Controller 注入。
public class AdminLoginService { // 实现 P2-01 的登录和会话有效期校验。
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15); // Token 登录成功后有效 15 分钟。
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}"); // 32 字节无填充 Base64 URL 编码应为 43 个字符。
    private final SecureRandom secureRandom = new SecureRandom(); // 使用密码学安全的随机数生成器。
    private final AdminUserRepository users; // 读取管理员密码摘要。
    private final AdminSessionRepository sessions; // 将 Token 哈希写入数据库并查询有效会话。
    private final PasswordEncoder passwordEncoder; // 使用 Spring Security 编码器校验密码。
    private final Clock clock; // 统一控制 Token 的签发和校验时间。

    public AdminLoginService(AdminUserRepository users, AdminSessionRepository sessions, PasswordEncoder passwordEncoder, Clock clock) { // 注入登录所需依赖。
        this.users = users; // 保存管理员账户仓库。
        this.sessions = sessions; // 保存会话仓库。
        this.passwordEncoder = passwordEncoder; // 保存密码编码器。
        this.clock = clock; // 保存 UTC 时钟。
    } // 构造方法结束。

    public LoginResult login(String username, String password) { // 校验凭据并创建新的短期会话。
        var account = users.findByUsername(username).orElseThrow(() -> new BadCredentialsException("用户名或密码错误")); // 不区分账号不存在与密码错误。
        if (!passwordEncoder.matches(password, account.passwordHash())) { // 用摘要验证输入密码，不比较明文。
            throw new BadCredentialsException("用户名或密码错误"); // 两种登录失败使用相同异常和文案。
        } // 密码校验结束。
        byte[] randomBytes = new byte[32]; // 为 Token 准备 256 位随机数据。
        secureRandom.nextBytes(randomBytes); // 从安全随机数生成器填充字节。
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes); // 生成不透明且适合 Bearer 请求头的 Token。
        Instant expiresAt = clock.instant().truncatedTo(ChronoUnit.MILLIS).plus(TOKEN_LIFETIME); // 用同一精度计算响应与数据库的到期时间。
        sessions.create(account.userId(), sha256(token), LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)); // 只持久化 Token 哈希和 UTC 到期时间。
        return new LoginResult(token, expiresAt); // 明文 Token 只交给本次登录响应使用。
    } // 登录流程结束。

    public Optional<Long> validateToken(String token) { // 供测试及后续管理员鉴权读取有效管理员 ID。
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) { // 拒绝缺失、格式错误或异常长度的 Token。
            return Optional.empty(); // 无效输入不查询数据库。
        } // Token 格式检查结束。
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); // 使用当前 UTC 时间检查数据库到期时间。
        return sessions.findActiveUserId(sha256(token), now); // 只按哈希查询未撤销且尚未到期的会话。
    } // 会话校验结束。

    private static byte[] sha256(String value) { // 将不透明 Token 转换为固定长度的数据库查找值。
        try { // JDK 提供 SHA-256 时正常返回摘要。
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); // 对生成的 ASCII Token 计算 32 字节摘要。
        } catch (NoSuchAlgorithmException exception) { // 极端运行环境缺少算法时停止登录。
            throw new IllegalStateException("运行环境不支持 SHA-256", exception); // 不在异常消息中包含 Token。
        } // 哈希计算结束。
    } // 摘要方法结束。

    public record LoginResult(String token, Instant expiresAt) { // 对应登录成功响应的两个字段。
    } // 登录结果类型结束。
} // 登录应用服务结束。