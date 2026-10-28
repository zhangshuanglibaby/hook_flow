package com.hookflow.endpoint.domain; // 测试接收端任务快照的数据边界。

import static org.junit.jupiter.api.Assertions.assertArrayEquals; // 核对密文复制后内容不变。
import static org.junit.jupiter.api.Assertions.assertEquals; // 核对快照密文仍可解密。
import static org.junit.jupiter.api.Assertions.assertFalse; // 核对字符串表示不泄露敏感内容。
import static org.junit.jupiter.api.Assertions.assertThrows; // 核对非法快照明确失败。
import java.security.SecureRandom; // 生成本次测试专用的随机主密钥。
import java.util.Base64; // 将主密钥编码为组件要求的标准 Base64。
import org.junit.jupiter.api.Test; // 标记不依赖数据库的测试方法。
import com.hookflow.endpoint.infrastructure.EndpointSigningSecretCipher; // 生成真实加密后的测试密文。

class EndpointTaskSnapshotTests { // 直接创建对象，不启动 Spring、HTTP 或 MySQL。
    @Test // 验证构造和读取均复制密文数组。
    void copiesCiphertextWithoutExposingSecrets() { // 覆盖快照的防修改和保密边界。
        EndpointSigningSecretCipher cipher = new EndpointSigningSecretCipher(newMasterKey()); // 使用随机有效主密钥。
        String signingSecret = cipher.generateSecret(); // 生成真实的独立签名密钥。
        byte[] source = cipher.encrypt(signingSecret); // 得到真实加密结果，而不是占位密文。
        byte[] expected = source.clone(); // 保存未被修改的密文用于比较。
        EndpointTaskSnapshot snapshot = new EndpointTaskSnapshot( // 建立只包含密文的快照对象。
                EndpointTargetType.EXTERNAL, "https://example.com/hook", 5000, source); // 此处只测试数据对象，不创建外部接收端。
        source[0] ^= 1; // 修改传给构造方法的原数组。
        assertArrayEquals(expected, snapshot.secretCiphertextSnapshot()); // 原数组修改不得污染快照。
        byte[] returned = snapshot.secretCiphertextSnapshot(); // 取得一份对外返回的密文副本。
        returned[0] ^= 1; // 修改调用方持有的副本。
        assertArrayEquals(expected, snapshot.secretCiphertextSnapshot()); // 读取结果修改也不得污染快照。
        assertEquals(signingSecret, cipher.decryptForDelivery(snapshot.secretCiphertextSnapshot())); // 原快照仍可在投递侧解密。
        assertFalse(snapshot.toString().contains(signingSecret)); // 字符串表示不得包含明文密钥。
        assertFalse(snapshot.toString().contains("example.com")); // 字符串表示也不输出 URL。
    } // 密文复制测试结束。

    @Test // 验证不完整的快照不能被构造。
    void rejectsMissingCiphertextAndInvalidConfiguration() { // 覆盖快照数据对象的输入检查。
        EndpointSigningSecretCipher cipher = new EndpointSigningSecretCipher(newMasterKey()); // 创建真实加密组件。
        byte[] ciphertext = cipher.encrypt(cipher.generateSecret()); // 为非密文错误路径准备有效密文。
        assertThrows(NullPointerException.class, () -> new EndpointTaskSnapshot(null, null, 5000, ciphertext)); // 目标类型不能缺失。
        assertThrows(IllegalArgumentException.class, () -> new EndpointTaskSnapshot(EndpointTargetType.SIMULATOR, null, 0, ciphertext)); // 超时不能低于下限。
        assertThrows(IllegalArgumentException.class, () -> new EndpointTaskSnapshot(EndpointTargetType.SIMULATOR, null, 15001, ciphertext)); // 超时不能高于上限。
        assertThrows(IllegalStateException.class, () -> new EndpointTaskSnapshot(EndpointTargetType.SIMULATOR, null, 5000, null)); // 密文不能缺失。
        assertThrows(IllegalStateException.class, () -> new EndpointTaskSnapshot(EndpointTargetType.SIMULATOR, null, 5000, new byte[0])); // 密文不能是空数组。
    } // 非法快照测试结束。

    private static String newMasterKey() { // 生成符合正式配置长度要求的随机测试主密钥。
        byte[] bytes = new byte[32]; // 使用 AES-256 所需的 32 字节。
        new SecureRandom().nextBytes(bytes); // 避免在测试代码中保存固定密钥。
        return Base64.getEncoder().encodeToString(bytes); // 转换为规范标准 Base64。
    } // 主密钥生成方法结束。
} // 快照纯单元测试类结束。