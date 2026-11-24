import { Award } from 'lucide-react';
import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Tooltip } from '@/components/ui/Tooltip';
import { usePostExpertise } from '../api';
import { expertiseIndex, formatPoints, TIER_LABELS } from '../logic';

/**
 * Small chip next to an author's name in a thread when they have high reputation in the post's topic.
 * All chips on a page share one cached request (`GET /posts/{id}/expertise`), so this never costs a query per comment.
 */
export function ExpertiseChip({ postId, userId }: { postId: number; userId: number | null | undefined }) {
  const q = usePostExpertise(postId);
  const index = useMemo(() => expertiseIndex(q.data), [q.data]);
  if (userId == null) return null;
  const e = index.get(userId);
  if (!e) return null;
  const label = e.topContributor ? `Top in ${e.topicName}` : `${TIER_LABELS[e.tier]} · ${e.topicName}`;
  return (
    <Tooltip content={`${formatPoints(e.points)} reputation in ${e.topicName}, earned from votes by other members. Open the ${e.topicName} leaderboard.`}>
      <Link
        to={`/topics/${e.topicSlug}/leaders`}
        aria-label={`${label}: ${formatPoints(e.points)} reputation in ${e.topicName}`}
        className="inline-flex h-[18px] items-center gap-1 rounded-full border border-accent/30 bg-accent/10 px-1.5 text-[10px] font-semibold text-accent hover:bg-accent/20"
      >
        <Award size={11} aria-hidden="true" />
        <span className="max-w-[140px] truncate">{label}</span>
      </Link>
    </Tooltip>
  );
}
