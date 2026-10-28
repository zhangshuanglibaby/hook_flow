package com.hookflow.endpoint.infrastructure; // 测试接收端签名密钥加密组件。

import static org.junit.jupiter.api.Assertions.assertEquals; // 核对解密结果和失败提示。
import static org.junit.jupiter.api.Assertions.assertFalse; // 核对密文不直接包含明文。
import static org.junit.jupiter.api.Assertions.assertNotEquals; // 核对独立生成的密钥和密文。
import static org.junit.jupiter.api.Assertions.assertThrows; // 核对错误输入明确失败。
import java.nio.charset.StandardCharsets; // 按固定编码检查密文字节。
import java.security.SecureRandom; // 为测试生成不同的有效环境主密钥。
import java.util.Arrays; // 制作被篡改或截断的密文副本。
import java.util.Base64; // 将测试主密钥编码成配置要求的格式。
import org.junit.jupiter.api.Test; // 标记不依赖数据库的单元测试。

class EndpointSigningSecretCipherTests { // 直接创建组件，不启动 Spring 或 MySQL。
    @Test // 验证两个接收端可获得不同密钥，且密文可以正确解密。
    void generatesIndependentSecretsAndEncryptsThem() { // 覆盖正常加解密路径。
        EndpointSigningSecretCipher cipher = new EndpointSigningSecretCipher(newMasterKey()); // 使用随机有效主密钥。
        String first = cipher.generateSecret(); // 模拟为第一个接收端生成密钥。
        String second = cipher.generateSecret(); // 模拟为第二个接收端生成密钥。
        assertNotEquals(first, second); // 不同接收端不得共享签名密钥。
        byte[] firstCiphertext = cipher.encrypt(first); // 加密第一个签名密钥。
        byte[] secondCiphertext = cipher.encrypt(second); // 加密第二个签名密钥。
        assertNotEquals(Arrays.toString(firstCiphertext), Arrays.toString(secondCiphertext)); // 两份密文不得相同。
        assertEquals(first, cipher.decryptForDelivery(firstCiphertext)); // 第一个密文只能还原其对应密钥。
        assertEquals(second, cipher.decryptForDelivery(secondCiphertext)); // 第二个密文只能还原其对应密钥。
        assertFalse(new String(firstCiphertext, StandardCharsets.ISO_8859_1).contains(first)); // 密文字节不直接包含第一个明文。
        assertNotEquals(Arrays.toString(firstCiphertext), Arrays.toString(cipher.encrypt(first))); // 同一密钥重复加密也使用不同随机数。
    } // 正常路径测试结束。

    @Test // 验证缺失、格式错误或长度错误的主密钥不能使用。
    void rejectsInvalidMasterKeys() { // 直接测试构造时的失败行为。
        assertThrows(IllegalStateException.class, () -> new EndpointSigningSecretCipher(null)); // 缺少主密钥时失败。
        assertThrows(IllegalStateException.class, () -> new EndpointSigningSecretCipher("not-base64")); // 非法 Base64 时失败。
        assertThrows(IllegalStateException.class, () -> new EndpointSigningSecretCipher(Base64.getEncoder().encodeToString(new byte[16]))); // 16 字节不足要求时失败。
        String nonCanonical = newMasterKey().replace("=", ""); // 去掉标准编码末尾必需的填充字符。
        assertThrows(IllegalStateException.class, () -> new EndpointSigningSecretCipher(nonCanonical)); // 非规范编码也失败。
    } // 主密钥错误路径测试结束。

    @Test // 验证密文篡改、截断及错误主密钥绝不回退到明文。
    void rejectsDamagedCiphertextAndWrongKey() { // 覆盖投递侧解密的失败行为。
        EndpointSigningSecretCipher cipher = new EndpointSigningSecretCipher(newMasterKey()); // 创建加密组件。
        byte[] ciphertext = cipher.encrypt(cipher.generateSecret()); // 获取一份真实生成的密文。
        byte[] tampered = ciphertext.clone(); // 复制密文，避免修改原始结果。
        tampered[tampered.length - 1] ^= 1; // 改动认证标签中的一个比特。
        byte[] unknownVersion = ciphertext.clone(); // 为版本错误单独准备副本。
        unknownVersion[0] = 2; // 写入当前实现不支持的格式版本。
        assertEquals("签名密钥密文无效", assertThrows(IllegalStateException.class, () -> cipher.decryptForDelivery(tampered)).getMessage()); // 篡改必须明确失败。
        assertThrows(IllegalStateException.class, () -> cipher.decryptForDelivery(unknownVersion)); // 未知版本必须失败。
        assertThrows(IllegalStateException.class, () -> cipher.decryptForDelivery(Arrays.copyOf(ciphertext, 13))); // 缺少认证标签的截断密文必须失败。
        assertThrows(IllegalStateException.class, () -> cipher.decryptForDelivery(null)); // 缺少密文必须失败。
        EndpointSigningSecretCipher wrongKey = new EndpointSigningSecretCipher(newMasterKey()); // 使用另一把随机主密钥。
        assertThrows(IllegalStateException.class, () -> wrongKey.decryptForDelivery(ciphertext)); // 错误主密钥不得得到明文。
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt("plain")); // 非规范签名密钥不得作为明文进入存储。
    } // 密文错误路径测试结束。

    private static String newMasterKey() { // 每次生成符合正式配置要求的测试主密钥。
        byte[] bytes = new byte[32]; // 使用要求的 32 字节长度。
        new SecureRandom().nextBytes(bytes); // 避免测试依赖固定密钥值。
        return Base64.getEncoder().encodeToString(bytes); // 返回规范的标准 Base64 配置值。
    } // 测试主密钥生成方法结束。
} // 纯单元测试类结束。