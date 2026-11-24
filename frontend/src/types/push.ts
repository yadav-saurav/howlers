/** Web push (D-040). */
export type PushCategory = 'REPLIES' | 'MENTIONS' | 'MESSAGES' | 'MODERATION' | 'FOLLOWERS' | 'ACTIVITY';

export interface PushConfig {
  available: boolean;
  /** VAPID public key (base64url) browsers subscribe with; null when push isn't set up on the server. */
  publicKey: string | null;
  unavailableReason: string | null;
  categories: PushCategory[];
  defaultCategories: PushCategory[];
}

export interface PushDevice {
  id: number;
  label: string;
  categories: PushCategory[];
  createdAt: string;
  lastUsedAt: string | null;
}

export interface PushTestResult {
  outcome: 'DELIVERED' | 'GONE' | 'REJECTED' | 'FAILED' | 'UNAVAILABLE';
  status: number;
  message: string;
}
