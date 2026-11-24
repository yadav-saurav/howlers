import type { CommunityRef, ID, Topic, UserSummary } from './api';

/** Mirrors docs/API.md → Topic reputation and Moderator recognition (D-020). */

export type ReputationTier = 'NONE' | 'CONTRIBUTOR' | 'TRUSTED' | 'EXPERT' | 'AUTHORITY';

export interface TierInfo {
  tier: ReputationTier;
  label: string;
  minPoints: number;
}

export interface ReputationPolicy {
  upvote: number;
  downvote: number;
  pairDailyCap: number;
  topicDailyCap: number;
  newAccountDays: number;
  newAccountWeight: number;
  minKarma: number;
  lowKarmaWeight: number;
  qualitySaturation: number;
  tiers: TierInfo[];
  topRank: number;
  topMinPoints: number;
}

export interface LeaderEntry {
  rank: number;
  user: UserSummary;
  points: number;
  tier: ReputationTier;
  topContributor: boolean;
  upvotes: number;
  downvotes: number;
}

export interface TopicLeaderboard {
  topic: Topic;
  entries: LeaderEntry[];
  contributors: number;
  computedAt: string | null;
  policy: ReputationPolicy;
}

export interface TopicOverview {
  topic: Topic;
  contributors: number;
  leaders: LeaderEntry[];
}

export interface ReputationOverview {
  topics: TopicOverview[];
  computedAt: string | null;
}

export interface TopicStanding {
  topic: Topic;
  points: number;
  tier: ReputationTier;
  rank: number;
  topContributor: boolean;
  upvotes: number;
  downvotes: number;
}

export interface UserReputation {
  hidden: boolean;
  topics: TopicStanding[];
  computedAt: string | null;
}

export interface Expertise {
  userId: ID;
  topicSlug: string;
  topicName: string;
  points: number;
  tier: ReputationTier;
  topContributor: boolean;
}

export interface PostExpertise {
  topics: Topic[];
  authors: Expertise[];
}

// ------------------------------------------------------------------ moderator recognition

export type ScoreComponent = 'consistency' | 'responsiveness' | 'appealQuality' | 'health' | 'peer';

export type ScoreComponents = Record<ScoreComponent, number | null>;

export interface ScoreInputs {
  decisions: number;
  decisionsReversed: number;
  reportsHandled: number;
  medianResponseMinutes: number | null;
  appealsUpheld: number;
  appealsOverturned: number;
  endorsements: number;
  coModerators: number;
  communityReports: number;
  communityReportsResolved: number;
  removalRate: number | null;
  prevRemovalRate: number | null;
}

export interface ModBadge {
  code: string;
  label: string;
  rule: string;
}

export interface ModeratorScore {
  moderator: UserSummary;
  community: CommunityRef;
  period: string;
  rank: number | null;
  score: number;
  ranked: boolean;
  components: ScoreComponents;
  inputs: ScoreInputs;
  badges: ModBadge[];
  computedAt: string;
}

export interface FormulaWeight {
  component: ScoreComponent;
  label: string;
  weight: number;
  definition: string;
}

export interface ScoreFormula {
  weights: FormulaWeight[];
  minActivity: number;
  responseHalfHours: number;
  peerFullAt: number;
  badges: ModBadge[];
  note: string;
}

export interface ModLeaderboard {
  period: string;
  periods: string[];
  community: CommunityRef | null;
  entries: ModeratorScore[];
  formula: ScoreFormula;
  computedAt: string | null;
}

export interface ModerationRecord {
  hidden: boolean;
  optedOut: boolean;
  entries: ModeratorScore[];
  formula: ScoreFormula;
}

export interface ModPeer {
  moderator: UserSummary;
  endorsedByMe: boolean;
  note: string | null;
  endorsementsThisPeriod: number;
}

export interface ModPeers {
  period: string;
  community: CommunityRef;
  peers: ModPeer[];
}

export interface ModeratorSettings {
  publicRanking: boolean;
}
