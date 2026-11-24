import type { ID, UserSummary } from './api';

/** Per-community automod (D-045). */
export type AutomodKind = 'KEYWORD' | 'REGEX' | 'LINK_LIMIT' | 'ACCOUNT_AGE' | 'KARMA' | 'DOMAIN';
export type AutomodAction = 'HOLD' | 'REMOVE' | 'FLAG';
export type AutomodScope = 'POSTS' | 'COMMENTS' | 'BOTH';

export interface AutomodRuleInput {
  name: string;
  kind: AutomodKind;
  /** KEYWORD: one phrase per line (* = any letters); REGEX: one RE2 pattern; DOMAIN: one domain per line. */
  pattern: string | null;
  /** LINK_LIMIT: most links; ACCOUNT_AGE: minimum days; KARMA: minimum karma. */
  threshold: number | null;
  appliesTo: AutomodScope;
  action: AutomodAction;
  /** Shown to the author when content is held or removed. */
  reason: string;
  enabled: boolean;
}

export interface AutomodRule extends AutomodRuleInput {
  id: ID;
  createdBy: UserSummary | null;
  createdAt: string;
  updatedAt: string;
  hits30d: number;
}

export interface AutomodHit {
  id: ID;
  ruleId: ID | null;
  ruleName: string;
  action: AutomodAction;
  outcome: AutomodAction;
  targetType: 'POST' | 'COMMENT';
  postId: ID | null;
  commentId: ID | null;
  author: UserSummary | null;
  detail: string;
  excerpt: string;
  createdAt: string;
}

export interface AutomodPreviewMatch {
  targetType: 'POST' | 'COMMENT';
  postId: ID;
  commentId: ID | null;
  author: UserSummary | null;
  excerpt: string;
  detail: string;
  /** Moderators and admins are exempt at write time. */
  authorExempt: boolean;
  createdAt: string;
}

export interface AutomodPreview {
  scannedPosts: number;
  scannedComments: number;
  matchCount: number;
  matches: AutomodPreviewMatch[];
  timedOut: number;
}
