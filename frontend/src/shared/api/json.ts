import { isLosslessNumber, parse, stringify } from 'lossless-json';

// 这里专门处理“事件内容 JSON”。普通 JSON.parse 会把超大数字转换成不准确的 JavaScript Number。
// 例如 9007199254740993 可能被错误地变成 9007199254740992，因此事件内容不能直接使用 JSON.parse。

// 解析后端响应：普通字段必须是浏览器能够安全处理的数字，payload 字段则保留高精度数字。
export function parseResponse(text: string): unknown {
  // payload=true 表示当前值位于事件内容内部，可以保留 lossless-json 的数字对象。
  function metadata(value: unknown, payload = false): unknown {
    if (payload) return value;

    // 后端的分页数量、内部 ID 等元数据必须转换成安全的 JavaScript 数字。
    if (isLosslessNumber(value)) {
      const numeric = Number(value.value);
      // 超出安全范围的 ID 如果继续使用，会查询到错误资源，因此直接拒绝。
      if (!Number.isFinite(numeric) || (Number.isInteger(numeric) && !Number.isSafeInteger(numeric))) {
        throw new Error('UNSAFE_NUMBER');
      }
      return numeric;
    }

    // 递归处理数组中的普通元数据。
    if (Array.isArray(value)) return value.map((item) => metadata(item));

    // 递归处理对象；只有名为 payload 的字段允许保留高精度数字。
    if (value !== null && typeof value === 'object') {
      return Object.fromEntries(
        Object.entries(value).map(([key, item]) => [key, metadata(item, key === 'payload')]),
      );
    }
    return value;
  }

  return metadata(parse(text));
}

// 把事件内容重新格式化为可读文本。lossless-json 会尽量保留原始数字精度。
export function payloadText(value: unknown): string {
  return stringify(value, undefined, 2) ?? 'null';
}

// 构造管理员创建测试事件的请求体。
// 这个接口故意不接收 eventId，因为事件 ID 必须由后端生成 UUID。
export function buildTestEventBody(eventType: string, endpointId: number, payload: string): string {
  // 先做前端体验校验，后端仍然是最终校验者。
  if (!eventType.trim() || [...eventType].length > 128) {
    throw new Error('事件类型需要 1–128 个字符，不能全为空白。');
  }
  if (!Number.isSafeInteger(endpointId) || endpointId < 1) {
    throw new Error('请选择有效的接收端。');
  }
  // 空文本不是合法 JSON，但字符串“null”是合法 JSON，所以这里只检查 trim 后是否为空。
  if (!payload.trim()) throw new Error('请输入 JSON，null 也是合法值。');

  // 只验证语法，不把解析结果重新序列化，避免无意中改变大数字。
  try {
    parse(payload);
  } catch {
    throw new Error('JSON 格式不正确，请检查双引号、逗号和括号。');
  }

  // 只拼接已经验证过的 payload；eventType 用 JSON.stringify 防止引号破坏请求结构。
  const body = `{"eventType":${JSON.stringify(eventType)},"endpointId":${endpointId},"payload":${payload}}`;
  // 后端限制的是整个 UTF-8 请求体，不只是 payload 文本，因此要对最终 body 计算字节数。
  if (new TextEncoder().encode(body).byteLength > 262144) {
    throw new Error('完整请求体超过 256 KiB，请减少事件内容。');
  }
  return body;
}
