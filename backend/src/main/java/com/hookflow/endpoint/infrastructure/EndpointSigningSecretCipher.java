package com.hookflow.endpoint.infrastructure; // 将签名密钥加密实现放在接收端基础设施层。

import java.nio.charset.StandardCharsets; // 固定签名密钥及加密上下文的字符编码。
import java.security.GeneralSecurityException; // 统一处理加解密操作失败。
import java.security.SecureRandom; // 生成不可预测的签名密钥和随机数。
import java.util.Base64; // 编码密钥，并校验主密钥格式。
import javax.crypto.Cipher; // 执行带身份认证的 AES-GCM 加解密。
import javax.crypto.spec.GCMParameterSpec; // 指定 GCM 随机数及认证标签长度。
import javax.crypto.spec.SecretKeySpec; // 将主密钥字节构造成 AES 密钥。
import org.springframework.beans.factory.annotation.Value; // 从现有配置读取环境主密钥。
import org.springframework.context.annotation.Profile; // 排除不使用正式配置的 skeleton 模式。
import org.springframework.stereotype.Component; // 向应用服务提供此组件。

@Profile("!skeleton") // 无数据库的骨架测试不创建加密组件。
@Component // 正式应用通过 Spring 注入同一加密组件。
public final class EndpointSigningSecretCipher { // 只负责生成、加密和按需解密接收端签名密钥。
    private static final byte VERSION = 1; // 密文首字节标记格式版本，避免误解码其他格式。
    private static final int NONCE_BYTES = 12; // 每次 AES-GCM 加密使用独立的 12 字节随机数。
    private static final int TAG_BITS = 128; // 使用 128 位认证标签检测密文篡改。
    private static final byte[] CONTEXT = "HookFlow/endpoint/signing/v1".getBytes(StandardCharsets.US_ASCII); // 将密文限定在接收端签名密钥用途。
    private final SecretKeySpec masterKey; // 只在内存中保存解码后的 AES 主密钥。
    private final SecureRandom random = new SecureRandom(); // 为每次生成和加密提供随机字节。

    public EndpointSigningSecretCipher(@Value("${hookflow.security.secret-encryption-key}") String encodedKey) { // 从既有配置取得主密钥。
        byte[] keyBytes; // 暂存标准 Base64 解码得到的原始字节。
        try { // 拒绝缺失或无法解码的主密钥。
            keyBytes = Base64.getDecoder().decode(encodedKey); // 解析环境变量提供的标准 Base64 文本。
        } catch (IllegalArgumentException | NullPointerException exception) { // 不把原始配置值写入异常消息。
            throw new IllegalStateException("配置错误：HOOKFLOW_SECRET_ENCRYPTION_KEY 必须是 32 字节密钥的标准 Base64 编码"); // 缺失或格式错误时明确失败。
        } // 主密钥解码结束。
        if (keyBytes.length != 32 || !Base64.getEncoder().encodeToString(keyBytes).equals(encodedKey)) { // 要求精确的 32 字节和规范编码。
            throw new IllegalStateException("配置错误：HOOKFLOW_SECRET_ENCRYPTION_KEY 必须是 32 字节密钥的标准 Base64 编码"); // 不允许使用弱化或含糊的密钥。
        } // 主密钥长度检查结束。
        this.masterKey = new SecretKeySpec(keyBytes, "AES"); // 建立 AES 密钥，不将明文写入数据库或日志。
    } // 主密钥初始化结束。

    public String generateSecret() { // 为一个接收端生成独立签名密钥。
        byte[] bytes = new byte[32]; // 每个新接收端获得 32 字节随机值。
        random.nextBytes(bytes); // 使用安全随机源填充，避免固定密钥。
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 返回便于一次性交付的 43 字符文本。
    } // 签名密钥生成结束。

    public byte[] encrypt(String signingSecret) { // 将新接收端的签名密钥变成可存储的密文。
        if (!validSecret(signingSecret)) { // 拒绝空值、非法编码或非 32 字节的密钥。
            throw new IllegalArgumentException("签名密钥格式无效"); // 不回显传入的敏感值。
        } // 明文格式检查结束。
        byte[] nonce = new byte[NONCE_BYTES]; // 每次加密都准备全新的 GCM 随机数。
        random.nextBytes(nonce); // 避免对同一主密钥重复使用固定随机数。
        try { // 加密失败不能返回明文或假密文。
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); // 选择带篡改检测的 AES-GCM。
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce)); // 使用主密钥和本次随机数初始化。
            cipher.updateAAD(CONTEXT); // 把用途标识纳入认证范围。
            byte[] encrypted = cipher.doFinal(signingSecret.getBytes(StandardCharsets.US_ASCII)); // 加密签名密钥并生成认证标签。
            byte[] result = new byte[1 + NONCE_BYTES + encrypted.length]; // 分配版本、随机数和加密结果的空间。
            result[0] = VERSION; // 把密文格式版本写在首字节。
            System.arraycopy(nonce, 0, result, 1, NONCE_BYTES); // 将解密所需随机数放在版本后。
            System.arraycopy(encrypted, 0, result, 1 + NONCE_BYTES, encrypted.length); // 附上密文及认证标签。
            return result; // 仅向存储边界返回二进制密文。
        } catch (GeneralSecurityException exception) { // 加密器无法正常工作时停止操作。
            throw new IllegalStateException("签名密钥加密失败"); // 不泄露签名密钥或主密钥。
        } // 加密操作结束。
    } // 密文生成结束。

    public String decryptForDelivery(byte[] ciphertext) { // 仅供后续投递边界按需解开任务密文快照。
        if (ciphertext == null || ciphertext.length < 1 + NONCE_BYTES + TAG_BITS / 8 + 1 || ciphertext[0] != VERSION) { // 拒绝缺失、截断或未知版本。
            throw new IllegalStateException("签名密钥密文无效"); // 绝不回退到明文。
        } // 密文外层格式检查结束。
        try { // 身份认证失败时不得使用任何解密结果。
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); // 使用与加密时相同的算法。
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, ciphertext, 1, NONCE_BYTES)); // 从密文中读取本次随机数。
            cipher.updateAAD(CONTEXT); // 核对密文原本属于接收端签名用途。
            byte[] plain = cipher.doFinal(ciphertext, 1 + NONCE_BYTES, ciphertext.length - 1 - NONCE_BYTES); // 校验标签后才得到明文。
            String secret = new String(plain, StandardCharsets.US_ASCII); // 还原签名时使用的文本密钥。
            if (!validSecret(secret)) { // 对认证通过但内容不合规范的密钥也拒绝使用。
                throw new IllegalStateException("签名密钥密文无效"); // 不把异常内容暴露给接口或日志。
            } // 解密内容检查结束。
            return secret; // 仅交给后续投递签名逻辑，不提供管理查询方法。
        } catch (GeneralSecurityException exception) { // 错误主密钥或损坏的密文会导致认证失败。
            throw new IllegalStateException("签名密钥密文无效"); // 明确失败，绝不退回明文。
        } // 解密操作结束。
    } // 投递侧解密接口结束。

    private boolean validSecret(String value) { // 检查签名密钥是否为规范的 32 字节 URL 安全编码。
        if (value == null) { // 空值没有可用的签名密钥。
            return false; // 阻止空值进入加密或投递。
        } // 空值检查结束。
        try { // 非法字符会由解码器抛出异常。
            byte[] decoded = Base64.getUrlDecoder().decode(value); // 还原签名密钥的原始随机字节。
            return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value); // 同时要求长度和编码完全一致。
        } catch (IllegalArgumentException exception) { // 非法 URL 安全 Base64 不是有效密钥。
            return false; // 不向调用方泄露输入内容。
        } // 签名密钥格式检查结束。
    } // 内部校验方法结束。
} // 接收端签名密钥加密组件结束。