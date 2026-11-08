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

/** 发送前编码：拿一个新的一次性 OTP，拼成 `[otp][0x00][payload]`（每次调用都走桥）。 */
export async function encodeWsMessage(
  payload: ArrayBuffer | Uint8Array,
): Promise<ArrayBuffer> {
  const { otp } = await DataChannel.createWsOtp();
  return encodeWsMessageWithOtp(otp, payload);
}

/**
 * 同步编码：用**已有**的 OTP 拼成 `[otp][0x00][payload]`（不调用桥）。
 *
 * 配合 {@link createWsOtpPool} / `createWsOtps` 使用，可把"每条消息一次桥调用"降到"每 N 条一次"。
 */
export function encodeWsMessageWithOtp(
  otp: string,
  payload: ArrayBuffer | Uint8Array,
): ArrayBuffer {
  const otpBytes = new TextEncoder().encode(otp);
  const data = payload instanceof Uint8Array ? payload : new Uint8Array(payload);
  const out = new Uint8Array(otpBytes.length + 1 + data.length);
  out.set(otpBytes, 0);
  out[otpBytes.length] = 0;
  out.set(data, otpBytes.length + 1);
  return out.buffer;
}

/**
 * 预先批量取回的 OTP 池：`take()` 是同步的，不发桥调用。
 *
 * ```ts
 * const pool = await createWsOtpPool(64);
 * ws.send(encodeWsMessageWithOtp(pool.take(), frame)); // 同步，无桥往返
 * ```
 *
 * 注意：池里的每个 OTP 在 **Server 校验时**才会被消费；池用尽后抛错。
 */
export class WsOtpPool {
  private index = 0;
  private readonly otps: string[];

  constructor(otps: string[]) {
    this.otps = otps;
  }

  get size(): number {
    return this.otps.length;
  }

  get remaining(): number {
    return this.otps.length - this.index;
  }

  take(): string {
    if (this.index >= this.otps.length) {
      throw new Error('WsOtpPool exhausted: call createWsOtpPool() again');
    }
    return this.otps[this.index++];
  }
}

/** 批量取一批 OTP 放进池子（一次桥调用）。 */
export async function createWsOtpPool(count = 64): Promise<WsOtpPool> {
  const { otps } = await DataChannel.createWsOtps({ count });
  return new WsOtpPool(otps);
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
 * 批量解码（**先验证、后交付**）：攒 K 条消息后一次性调 `validateAndConsumeOtps`。
 *
 * ⚠️ 只有在能接受"最多 K 条消息的额外延迟"时使用；返回时每条都已验证，
 * `ok: false` 的必须丢弃。
 */
export async function decodeWsMessagesBatch(
  messages: Array<ArrayBuffer | Uint8Array | Blob>,
): Promise<DecodedWsMessage[]> {
  const parsed: Array<{ otp?: string; payload?: Uint8Array; error?: string }> = [];
  for (const message of messages) {
    let bytes: Uint8Array;
    if (message instanceof Blob) {
      bytes = new Uint8Array(await message.arrayBuffer());
    } else if (message instanceof Uint8Array) {
      bytes = message;
    } else {
      bytes = new Uint8Array(message);
    }
    if (bytes.length === 0) {
      parsed.push({ error: 'empty message' });
      continue;
    }
    const separator = bytes.indexOf(0);
    if (separator < 0) {
      parsed.push({ error: 'missing OTP separator (0x00)' });
      continue;
    }
    if (separator === 0) {
      parsed.push({ error: new TextDecoder().decode(bytes.subarray(1)) || 'server reported an error' });
      continue;
    }
    const otp = new TextDecoder().decode(bytes.subarray(0, separator));
    parsed.push({ otp, payload: bytes.slice(separator + 1) });
  }

  const otps = parsed.filter((item) => item.otp).map((item) => item.otp as string);
  let validations: boolean[] = [];
  if (otps.length > 0) {
    const result = await DataChannel.validateAndConsumeOtps({ otps });
    validations = result.results;
  }

  let cursor = 0;
  return parsed.map((item) => {
    if (!item.otp) {
      return { ok: false, error: item.error };
    }
    const valid = validations[cursor++] === true;
    if (!valid) {
      return { ok: false, error: 'invalid or reused OTP (message discarded)' };
    }
    const payload = new Uint8Array(item.payload as Uint8Array);
    return { ok: true, payload: payload.buffer };
  });
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
