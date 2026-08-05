export type GlobalAction = "BACK" | "HOME" | "RECENTS";
export type ControlCommand =
  | { type: "tap"; sequence: number; x: number; y: number }
  | { type: "swipe"; sequence: number; fromX: number; fromY: number; toX: number; toY: number; durationMs: number }
  | { type: "longPress"; sequence: number; x: number; y: number }
  | { type: "globalAction"; sequence: number; action: GlobalAction }
  | { type: "setText"; sequence: number; text: string };
export interface Device { id: string; name: string; ownerId: string; role: "parent"; }
export interface TrustedHelper { parentDeviceId: string; helperUserId: string; helperName: string; revokedAt: unknown | null; }
export interface Session { id: string; parentDeviceId: string; parentOwnerId: string; helperUserId: string; participantIds: string[]; state: string; }

