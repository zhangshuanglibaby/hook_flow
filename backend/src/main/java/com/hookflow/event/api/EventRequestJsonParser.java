package com.hookflow.event.api; // 请求解析属于事件接口层。

import com.hookflow.event.domain.CanonicalEventPayload; // 表示规范化结果。
import com.hookflow.event.domain.EventPayloadCanonicalizer; // 复用已创建的规范化规则。
import java.io.IOException; // 处理读取请求体时的错误。
import java.io.InputStream; // 按流读取请求体，避免预先接收无限大的字节数组。
import java.nio.ByteBuffer; // 将受限的请求字节交给 UTF-8 解码器。
import java.nio.charset.CharacterCodingException; // 识别非法 UTF-8 字节序列。
import java.nio.charset.CodingErrorAction; // 指定遇到非法字节时直接报错。
import java.nio.charset.StandardCharsets; // 使用明确的 UTF-8 编码。
import org.springframework.http.HttpStatus; // 使用 HTTP 400 表示非法请求。
import org.springframework.web.server.ResponseStatusException; // 接入项目现有的统一错误响应。
import tools.jackson.core.JacksonException; // 处理非法 JSON。
import tools.jackson.databind.DeserializationFeature; // 配置精确数值和严格解析。
import tools.jackson.databind.JsonNode; // 表示解析后的 JSON 树。
import tools.jackson.databind.json.JsonMapper; // 负责解析 JSON。

public final class EventRequestJsonParser { // 只解析请求，不提供 HTTP 接口或持久化。
    public static final int MAX_REQUEST_BYTES = 256 * 1024; // 整个请求体最多 262144 字节。
    private final EventPayloadCanonicalizer canonicalizer = new EventPayloadCanonicalizer(); // 规范化 payload。
    private final JsonMapper mapper = JsonMapper.builder() // 创建本类专用的解析器。
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS) // 小数使用精确十进制表示。
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS) // 大整数不因 long 溢出而损失精度。
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) // 拒绝一个 JSON 值后还有其他值。
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY) // 拒绝含重复对象键的歧义输入。
            .build(); // 完成解析器配置。

    public ParsedEventJson parse(InputStream input) { // 输入是尚未被读入内存的完整请求体。
        if (input == null) { // 没有请求体可读。
            throw badRequest(); // 交给统一异常处理器返回 400。
        }

        byte[] bytes; // 保存最多「上限加 1」字节，以判断是否超限。
        try { // 读取请求体可能发生 I/O 错误。
            bytes = input.readNBytes(MAX_REQUEST_BYTES + 1); // 多读 1 字节即可发现超限，不读完整超大请求。
        } catch (IOException exception) { // 无法取得有效请求体。
            throw badRequest(); // 不在错误响应中暴露输入或内部异常。
        }
        if (bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES) { // 空请求体或超过 256 KiB。
            throw badRequest(); // 在 JSON 解析之前拒绝。
        }

        String text; // 保存经过严格 UTF-8 解码的请求文本。
        try { // 非法 UTF-8 不应被悄悄替换成其他字符。
            text = StandardCharsets.UTF_8.newDecoder() // 创建严格的 UTF-8 解码器。
                    .onMalformedInput(CodingErrorAction.REPORT) // 拒绝格式错误的字节序列。
                    .onUnmappableCharacter(CodingErrorAction.REPORT) // 拒绝不能映射的字节。
                    .decode(ByteBuffer.wrap(bytes)).toString(); // 解码刚才受大小限制的字节。
        } catch (CharacterCodingException exception) { // 请求体不是合法 UTF-8。
            throw badRequest(); // 按非法请求处理。
        }

        JsonNode root; // 保存整个请求对象。
        try { // JSON 语法错误由 Jackson 报告。
            root = mapper.readTree(text); // 解析一次，保留精确数值节点。
        } catch (JacksonException exception) { // JSON 语法或结构不合法。
            throw badRequest(); // 不回显请求内容。
        }
        if (root == null || !root.isObject() || !root.has("payload")) { // 根必须是对象，且必须出现 payload 键。
            throw badRequest(); // 注意 payload:null 合法，不能用值是否为 null 判断键是否存在。
        }

        CanonicalEventPayload payload = canonicalizer.canonicalize(root.get("payload")); // 规范化并计算 SHA-256。
        return new ParsedEventJson(root, payload); // 返回解析树及规范化结果，暂不写入数据库。
    }

    private static ResponseStatusException badRequest() { // 集中生成相同的请求错误。
        return new ResponseStatusException(HttpStatus.BAD_REQUEST); // 复用现有 400 错误响应格式。
    }

    public record ParsedEventJson(JsonNode request, CanonicalEventPayload payload) { // 保存本次解析的两个结果。
    }
}