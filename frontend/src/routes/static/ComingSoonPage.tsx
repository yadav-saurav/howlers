import { Construction } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { buttonClasses } from '@/components/ui/Button';
import { Card } from '@/components/ui/Display';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns } from '@/layouts/PageColumns';

const FEATURES: Record<string, { title: string; phase: string; body: string }> = {
  assistant: {
    title: 'Community AI',
    phase: 'Phase 5',
    body: 'Ask questions answered from real community discussions, with citations that link to the exact posts and comments. Private communities and deleted content will be respected.',
  },
  collections: {
    title: 'Smart Collections',
    phase: 'Phase 4',
    body: 'A personal knowledge library: organise saved posts, comments and resources into collections with notes and tags. For now, use Save on any post.',
  },
  podcasts: {
    title: 'Community Podcasts',
    phase: 'Phase 6',
    body: 'Long-form audio and video episodes built from community discussions, with chapters, transcripts and links to every cited source.',
  },
  funding: {
    title: 'Moderator support',
    phase: 'Phase 8',
    body: 'Voluntary contributions to a community’s moderator pool with transparent allocation. It stays disabled until a payment provider and compliance requirements are in place.',
  },
  leaderboard: {
    title: 'Moderator leaderboard',
    phase: 'Phase 9',
    body: 'Recognition for moderators based on quality-weighted, transparent metrics — never raw removal counts.',
  },
};

export default function ComingSoonPage() {
  const { feature = '' } = useParams();
  const f = FEATURES[feature] ?? { title: 'Coming soon', phase: 'Later phase', body: 'This part of the product is not built yet.' };
  useDocumentTitle(f.title);
  return (
    <PageColumns>
      <Card className="mx-4 mt-6 px-6 py-12 text-center sm:mx-0">
        <span className="mx-auto mb-4 inline-flex h-11 w-11 items-center justify-center rounded-lg border border-border bg-surface-2 text-muted">
          <Construction size={20} />
        </span>
        <p className="text-xs font-semibold uppercase tracking-wider text-accent">{f.phase}</p>
        <h1 className="mt-1 font-serif text-2xl font-semibold">{f.title}</h1>
        <p className="mx-auto mt-3 max-w-md text-sm text-muted">{f.body}</p>
        <p className="mx-auto mt-2 max-w-md text-xs text-faint">Not available in this build. Nothing on this page collects data.</p>
        <Link to="/home" className={buttonClasses('secondary', 'sm', 'mt-6')}>Back to home</Link>
      </Card>
    </PageColumns>
  );
}
