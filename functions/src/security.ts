import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

export const INVITE_TTL_MS = 10 * 60 * 1000;
export const SESSION_TTL_MS = 15 * 60 * 1000;
export const METADATA_TTL_MS = 30 * 24 * 60 * 60 * 1000;

export function generatePairingCode(): string {
  const value = randomBytes(5).readUIntBE(0, 5) % 100_000_000;
  return value.toString().padStart(8, "0");
}

export function hashPairingCode(code: string, pepper: string): string {
  return createHash("sha256").update(`${pepper}:${normalizeCode(code)}`).digest("hex");
}

export function normalizeCode(code: string): string {
  const normalized = code.replace(/[\s-]/g, "");
  if (!/^\d{8}$/.test(normalized)) throw new Error("invalid_code");
  return normalized;
}

export function safeHashEquals(a: string, b: string): boolean {
  const left = Buffer.from(a, "hex"); const right = Buffer.from(b, "hex");
  return left.length === right.length && timingSafeEqual(left, right);
}

export function requireString(value: unknown, name: string, max = 128): string {
  if (typeof value !== "string" || !value.trim() || value.length > max) throw new Error(`invalid_${name}`);
  return value.trim();
}

