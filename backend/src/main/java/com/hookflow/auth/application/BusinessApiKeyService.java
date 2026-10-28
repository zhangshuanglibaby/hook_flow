package com.hookflow.auth.application; // 业务 Key 的轮换与校验属于认证模块应用层。

import java.nio.charset.StandardCharsets; // 明确计算 Key 摘要时使用 ASCII 编码。
import java.security.MessageDigest; // 使用 SHA-256 计算数据库查询所需的摘要。
import java.security.NoSuchAlgorithmException; // 处理运行环境缺少 SHA-256 的异常。
import java.security.SecureRandom; // 生成不可预测的业务 Key。
import java.time.Clock; // 使用项目统一注入的 UTC 时钟。
import java.time.LocalDateTime; // 对应数据库的 UTC DATETIME(3) 字段。
import java.time.ZoneOffset; // 明确按 UTC 转换时间。
import java.time.temporal.ChronoUnit; // 将写入时间截取到毫秒精度。
import java.util.Base64; // 将随机字节编码为适合 HTTP 请求头的文本。
import java.util.regex.Pattern; // 限制接受校验的 Key 格式和长度。
import org.springframework.context.annotation.Profile; // 排除无数据库的 skeleton 配置。
import org.springframework.stereotype.Service; // 将应用服务注册为 Spring 组件。
import org.springframework.transaction.annotation.Isolation; // 指定轮换事务的隔离级别。
import org.springframework.transaction.annotation.Transactional; // 让撤销和创建处于同一事务。
import com.hookflow.auth.infrastructure.BusinessApiKeyRepository; // 调用认证模块的数据库访问类。

@Profile("!skeleton") // 正式数据库配置才启用业务 Key 服务。
@Service // 供后续轮换接口和鉴权过滤器注入。
public class BusinessApiKeyService { // 实现 P2-03 的轮换及有效性判断。
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}"); // 32 字节随机数的无填充 Base64 URL 文本为 43 字符。
    private final SecureRandom secureRandom = new SecureRandom(); // 使用密码学安全随机数生成器。
    private final BusinessApiKeyRepository keys; // 保存业务 Key 数据库访问组件。
    private final Clock clock; // 保存项目统一的可控 UTC 时钟。

    public BusinessApiKeyService(BusinessApiKeyRepository keys, Clock clock) { // 由 Spring 注入仓库和时钟。
        this.keys = keys; // 保存仓库，供轮换和校验使用。
        this.clock = clock; // 保存时钟，避免直接依赖机器本地时间。
    } // 构造方法结束。

    @Transactional(isolation = Isolation.READ_COMMITTED) // 从取得行锁到撤销、插入、提交，保持在同一个事务中。
    public String rotate() { // 返回仅供本次成功响应使用的新 Key 明文。
        keys.lockRotationGuard(); // 锁定固定管理员行，使首次及后续并发轮换串行执行。
        LocalDateTime now = LocalDateTime.ofInstant( // 构造数据库使用的 UTC 时间。
                clock.instant().truncatedTo(ChronoUnit.MILLIS), // 与 DATETIME(3) 的毫秒精度一致。
                ZoneOffset.UTC // 不使用服务器本地时区。
        ); // 本次轮换的统一时间确定完毕。
        byte[] randomBytes = new byte[32]; // 为新 Key 准备 256 位随机数据。
        secureRandom.nextBytes(randomBytes); // 填入不可预测的随机字节。
        String apiKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes); // 编码为可放入业务请求头的明文。
        keys.revokeActive(now); // 在同一事务内撤销此前所有有效业务 Key。
        keys.create(sha256(apiKey), now); // 只把新 Key 的摘要和创建时间写入数据库。
        return apiKey; // 事务成功提交后，调用方才会取得此明文并生成响应。
    } // 轮换方法结束；异常会使撤销和插入一起回滚。

    public boolean isActive(String apiKey) { // 判断请求提供的业务 Key 是否仍有效。
        if (apiKey == null || !KEY_FORMAT.matcher(apiKey).matches()) { // 缺失或格式不符合签发规则时不查询数据库。
            return false; // 错误格式不能获得业务身份。
        } // 输入格式检查结束。
        return keys.isActive(sha256(apiKey)); // 只用摘要查询数据库中未撤销的记录。
    } // Key 校验方法结束。

    private static byte[] sha256(String value) { // 将生成的 ASCII 凭据转换为固定长度摘要。
        try { // 正常 Java 运行环境应提供 SHA-256。
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); // 返回 32 字节摘要，不保存明文。
        } catch (NoSuchAlgorithmException exception) { // 算法不可用时停止操作。
            throw new IllegalStateException("运行环境不支持 SHA-256", exception); // 异常消息不包含业务 Key。
        } // 摘要计算结束。
    } // 摘要方法结束。
} // 业务 Key 应用服务结束。