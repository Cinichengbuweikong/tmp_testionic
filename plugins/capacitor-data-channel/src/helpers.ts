import { DataChannel } from './core';

/**
 * WebSocket 消息协议 helper（供 WebView 使用）。
 *
 * 协议（因为 WebSocket 消息没有头，所以自定义）：
 *
 * ```
 * 正常消息（双向）：[OTP 字符串(43 字符 ASCII)][0x00][payload 原始二进制]
 * 错误消息（Server→Client）：[0x00][错误文本 UTF-8]
 * ```
 *
 * - OTP 是 base64url（`A-Za-z0-9_-`），永远不含 `0x00`，所以按**第一个 `0x00`** 分割是安全的；
 * - payload 里可以包含 `0x00`（只按第一个分隔）；
 * - 错误消息首字节是 `0x00`（OTP 为空），与正常消息天然区分。
 */

/** 发送前编码：拿一个新的一次性 OTP，拼成 `[otp][0x00][payload]`。 */
export async function encodeWsMessage(
  payload: ArrayBuffer | Uint8Array,
): Promise<ArrayBuffer> {
  const { otp } = await DataChannel.createWsOtp();
  const otpBytes = new TextEncoder().encode(otp);
  const data = payload instanceof Uint8Array ? payload : new Uint8Array(payload);
  const out = new Uint8Array(otpBytes.length + 1 + data.length);
  out.set(otpBytes, 0);
  out[otpBytes.length] = 0;
  out.set(data, otpBytes.length + 1);
  return out.buffer;
}

/** 解码结果。 */
export interface DecodedWsMessage {
  /** 是否校验通过（true 时才可以使用 payload）。 */
  ok: boolean;
  /** 原始数据（仅 `ok: true` 时存在）。 */
  payload?: ArrayBuffer;
  /** 错误描述（`ok: false` 时）。 */
  error?: string;
}

/**
 * 收到消息后解码：校验 OTP 并返回原始数据。
 *
 * - 错误消息（首字节 `0x00`）→ `{ ok: false, error }`；
 * - 正常消息 → 调 `validateAndConsumeOTP`（消费该 nonce）：
 *   - 通过 → `{ ok: true, payload }`
 *   - 失败（被篡改/重放/伪造）→ `{ ok: false, error }`，**调用方必须丢弃数据**。
 */
export async function decodeWsMessage(data: ArrayBuffer | Uint8Array | Blob): Promise<DecodedWsMessage> {
  let bytes: Uint8Array;
  if (data instanceof Blob) {
    bytes = new Uint8Array(await data.arrayBuffer());
  } else if (data instanceof Uint8Array) {
    bytes = data;
  } else {
    bytes = new Uint8Array(data);
  }

  if (bytes.length === 0) {
    return { ok: false, error: 'empty message' };
  }
  const separator = bytes.indexOf(0);
  if (separator < 0) {
    return { ok: false, error: 'missing OTP separator (0x00)' };
  }
  if (separator === 0) {
    // 错误消息：[0x00][errorText]
    const text = new TextDecoder().decode(bytes.subarray(1));
    return { ok: false, error: text || 'server reported an error' };
  }
  const otp = new TextDecoder().decode(bytes.subarray(0, separator));
  const result = await DataChannel.validateAndConsumeOTP({ otp });
  if (!result.valid) {
    return { ok: false, error: 'invalid or reused OTP (message discarded)' };
  }
  const payload = bytes.slice(separator + 1);
  return { ok: true, payload: payload.buffer };
}
