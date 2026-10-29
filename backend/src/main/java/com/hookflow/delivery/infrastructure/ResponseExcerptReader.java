package com.hookflow.delivery.infrastructure; // 有限响应摘要属于 HTTP 基础设施层。

import java.io.IOException; // 响应流读取失败时保留网络错误语义。
import java.io.InputStream; // 从响应正文流读取有限字节。
import java.nio.ByteBuffer; // 将有限字节交给 UTF-8 解码器。
import java.nio.charset.CodingErrorAction; // 处理截断处或正文中的非法 UTF-8 字节。
import java.nio.charset.StandardCharsets; // 按 UTF-8 生成可存储的文本摘要。
import java.util.Objects; // 拒绝缺失的响应流。

public final class ResponseExcerptReader { // 只负责生成有字节上限的响应摘要。
    public static final int MAX_BYTES = 4 * 1024; // 与数据库的 4096 字节上限一致。

    private ResponseExcerptReader() { // 工具类不需要实例。
    } // 私有构造方法结束。

    public static String read(InputStream input) throws IOException { // 调用方负责关闭响应及其流。
        byte[] prefix = Objects.requireNonNull(input, "响应流缺失").readNBytes(MAX_BYTES); // 最多读取前 4096 字节，不继续读取余下正文。
        return StandardCharsets.UTF_8.newDecoder() // 建立本次摘要专用的 UTF-8 解码器。
                .onMalformedInput(CodingErrorAction.IGNORE) // 忽略非法或被截断的字节序列，避免替换字符使摘要超限。
                .onUnmappableCharacter(CodingErrorAction.IGNORE) // 忽略无法映射的字符，不扩大输出字节数。
                .decode(ByteBuffer.wrap(prefix)) // 只解码已读取的有限字节。
                .toString(); // 返回 UTF-8 重新编码后仍不超过 4096 字节的摘要。
    } // 摘要读取方法结束。
} // 有限响应摘要工具类结束。