import { describe, expect, it } from "vitest";
import { generatePairingCode, hashPairingCode, normalizeCode, safeHashEquals } from "./security";

describe("pairing codes", () => {
  it("generates fixed-width numeric codes", () => expect(generatePairingCode()).toMatch(/^\d{8}$/));
  it("normalizes display separators", () => expect(normalizeCode("1234-5678")).toBe("12345678"));
  it("rejects malformed codes", () => expect(() => normalizeCode("1234ABCD")).toThrow("invalid_code"));
  it("hashes with a server-only pepper", () => {
    const a = hashPairingCode("12345678", "pepper");
    expect(safeHashEquals(a, hashPairingCode("1234 5678", "pepper"))).toBe(true);
    expect(safeHashEquals(a, hashPairingCode("12345678", "different"))).toBe(false);
  });
});
