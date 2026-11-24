import type { UserSummary } from './api';

export type ScanStatus = 'CLEAN' | 'REJECTED' | 'UNSCANNED';
/** STRUCTURAL: type and structure checks only (not an antivirus scan). CLAMAV: also scanned by ClamAV (D-029). */
export type ScanEngine = 'STRUCTURAL' | 'CLAMAV';
export type KeyMode = 'FRAGMENT' | 'PASSPHRASE';
export type LinkStatus = 'ACTIVE' | 'EXPIRED' | 'REVOKED' | 'EXHAUSTED';
export type AccessEvent =
  | 'VIEWED'
  | 'DOWNLOADED'
  | 'WRONG_PASSWORD'
  | 'LOCKED'
  | 'EXPIRED'
  | 'REVOKED'
  | 'LIMIT_REACHED'
  | 'FILE_DELETED';

export interface FileItem {
  id: number;
  name: string;
  contentType: string;
  sizeBytes: number;
  e2ee: boolean;
  scanStatus: ScanStatus;
  scanDetail: string | null;
  scanEngine: ScanEngine;
  sha256: string;
  activeLinks: number;
  createdAt: string;
  updatedAt: string;
}

export interface FileUsage {
  usedBytes: number;
  quotaBytes: number;
  maxFileBytes: number;
  fileCount: number;
}

export interface WrappedKeyDto {
  wrappedKey: string;
  salt: string;
  iterations: number;
}

export interface CreateLinkBody {
  expiresAt?: string | null;
  password?: string | null;
  maxDownloads?: number | null;
  keyMode?: KeyMode | null;
  wrappedKey?: WrappedKeyDto | null;
}

export interface ShareLink {
  id: number;
  fileId: number;
  tokenHint: string;
  status: LinkStatus;
  createdAt: string;
  expiresAt: string | null;
  hasPassword: boolean;
  maxDownloads: number | null;
  downloadCount: number;
  keyMode: KeyMode | null;
  revokedAt: string | null;
  lastAccessAt: string | null;
}

export interface CreatedLink {
  link: ShareLink;
  token: string;
}

export interface AccessEntry {
  id: number;
  at: string;
  event: AccessEvent;
  viewer: UserSummary | null;
  visitor: string | null;
}

export interface ShareMeta {
  fileName: string | null;
  contentType: string | null;
  sizeBytes: number;
  e2ee: boolean;
  keyMode: KeyMode | null;
  expiresAt: string | null;
  requiresPassword: boolean;
  downloadsRemaining: number | null;
  scanStatus: ScanStatus;
  scanEngine: ScanEngine;
}

export interface ShareUnlock {
  downloadUrl: string;
  expiresAt: string;
  wrappedKey: WrappedKeyDto | null;
}
