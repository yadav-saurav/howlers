import { BadgeCheck } from 'lucide-react';
import { Badge } from '@/components/ui/Display';
import { Tooltip } from '@/components/ui/Tooltip';
import type { ModBadge, ModeratorScore, ScoreFormula } from '@/types/recognition';
import { explainScore, formatMinutes, percent } from '../logic';

/** The raw rows behind each component, in words, so nobody has to trust a bare number. */
function evidence(s: ModeratorScore, component: string): string {
  const i = s.inputs;
  switch (component) {
    case 'consistency':
      return i.decisions ? `${i.decisions - i.decisionsReversed} of ${i.decisions} removals/bans stood` : 'No removals or bans this period';
    case 'responsiveness':
      return i.reportsHandled ? `Median ${formatMinutes(i.medianResponseMinutes)} over ${i.reportsHandled} reports` : 'No reports handled this period';
    case 'appealQuality':
      return i.appealsUpheld + i.appealsOverturned
        ? `${i.appealsUpheld} upheld, ${i.appealsOverturned} overturned`
        : 'No appeals decided this period';
    case 'health':
      return i.communityReports
        ? `${i.communityReportsResolved} of ${i.communityReports} reports resolved; removal rate ${percent(i.removalRate)} (was ${percent(i.prevRemovalRate)})`
        : 'No reports filed in the community this period';
    case 'peer':
      return i.coModerators ? `${i.endorsements} endorsement${i.endorsements === 1 ? '' : 's'} from ${i.coModerators} co-moderator${i.coModerators === 1 ? '' : 's'}` : 'No co-moderators';
    default:
      return '';
  }
}

export function ModBadges({ badges }: { badges: ModBadge[] }) {
  if (!badges.length) return null;
  return (
    <span className="flex flex-wrap gap-1">
      {badges.map((b) => (
        <Tooltip key={b.code} content={b.rule}>
          <button type="button" className="inline-flex rounded-full">
            <Badge tone="accent" className="rounded-full px-2"><BadgeCheck size={11} aria-hidden="true" /> {b.label}</Badge>
          </button>
        </Tooltip>
      ))}
    </span>
  );
}

export function ScoreBreakdown({ score, formula }: { score: ModeratorScore; formula: ScoreFormula }) {
  const { rows } = explainScore(score.components, formula.weights);
  return (
    <div className="space-y-2">
      <table className="w-full text-left text-xs">
        <caption className="sr-only">How the score of {score.score} is made up</caption>
        <thead className="text-faint">
          <tr>
            <th scope="col" className="py-1 font-medium">Component</th>
            <th scope="col" className="py-1 text-right font-medium">Value</th>
            <th scope="col" className="py-1 text-right font-medium">Weight</th>
            <th scope="col" className="py-1 text-right font-medium">Points</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {rows.map((r) => (
            <tr key={r.component} className={r.value == null ? 'text-faint' : undefined}>
              <th scope="row" className="py-1.5 pr-2 font-normal">
                <span className="block font-medium text-text">{r.label}</span>
                <span className="block text-faint">{evidence(score, r.component)}</span>
              </th>
              <td className="py-1.5 text-right tabular-nums">{percent(r.value)}</td>
              <td className="py-1.5 text-right tabular-nums">{r.value == null ? 'n/a' : percent(r.effectiveWeight)}</td>
              <td className="py-1.5 text-right font-semibold tabular-nums">{r.value == null ? '—' : r.contribution.toFixed(1)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-[11px] text-faint">Components without data this period drop out and the remaining weights are rescaled.</p>
    </div>
  );
}

/** The whole formula, from the server's own constants. */
export function FormulaPanel({ formula }: { formula: ScoreFormula }) {
  return (
    <div className="space-y-3 text-sm">
      <p className="text-muted">{formula.note}</p>
      <ul className="space-y-2">
        {formula.weights.map((w) => (
          <li key={w.component}>
            <span className="font-medium">{w.label}</span> <span className="tabular-nums text-faint">× {w.weight.toFixed(2)}</span>
            <p className="text-xs text-muted">{w.definition}</p>
          </li>
        ))}
      </ul>
      <p className="text-xs text-muted">
        Ranked once a moderator has at least {formula.minActivity} removals, bans or handled reports in the month. Moderators can opt out
        of public ranking in Settings.
      </p>
      <div>
        <h3 className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted">Badges</h3>
        <ul className="space-y-1 text-xs text-muted">
          {formula.badges.map((b) => (
            <li key={b.code}><span className="font-medium text-text">{b.label}:</span> {b.rule}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}
