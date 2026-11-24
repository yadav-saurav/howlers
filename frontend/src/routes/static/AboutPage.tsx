import { brand } from '@/design-system/brand';
import { StaticPage } from './StaticPage';

export default function AboutPage() {
  return (
    <StaticPage title={`About ${brand.name}`}>
      <p>
        {brand.name} is a community discussion platform built around a simple idea: communities should generate
        knowledge, not just content. The familiar pieces are here — communities, posts, threaded comments and voting —
        arranged so that the best thinking is easier to find and easier to keep.
      </p>
      <h2>What we are building</h2>
      <ul>
        <li><strong>Readable discussions.</strong> Compact feeds, editorial typography and comment threads that stay navigable on any screen.</li>
        <li><strong>Ranking that values quality.</strong> Feeds weigh vote ratio, discussion activity and freshness instead of raw vote counts alone.</li>
        <li><strong>Knowledge tools.</strong> Context Mode, Thread Digests, Smart Collections and a community-grounded assistant that cites real posts are on the roadmap.</li>
        <li><strong>Support for the people who keep communities healthy.</strong> Moderator recognition and voluntary community funding are planned with transparent rules.</li>
      </ul>
      <h2>What we won’t do</h2>
      <p>
        We won’t label anyone a verified expert — reputation reflects contribution and peer appreciation, not credentials.
        We won’t claim end-to-end encryption or real payments until they are actually implemented and audited.
      </p>
      <h2>Status</h2>
      <p>
        {brand.name} is under active development. Features marked “Coming next” in the navigation are not available yet;
        everything else you can click is wired to the live backend.
      </p>
    </StaticPage>
  );
}
