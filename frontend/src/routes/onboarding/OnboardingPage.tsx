import { AnimatePresence, motion } from 'framer-motion';
import { Check, Monitor, Moon, Sun } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/Button';
import { Avatar, Skeleton } from '@/components/ui/Display';
import { FormAlert } from '@/components/ui/Field';
import { ErrorState } from '@/components/ui/States';
import { toast } from '@/components/ui/Toast';
import { useAuth } from '@/features/auth/hooks';
import { useCompleteOnboarding, useOnboardingSuggestions, useSkipOnboarding, useTopics } from '@/features/onboarding/hooks';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { useTheme, type ThemePref } from '@/hooks/useTheme';
import { errorMessage } from '@/services/apiError';
import { cn, displayName, pluralize } from '@/utils/format';

const STEPS = ['Interests', 'Communities', 'People', 'Preferences'] as const;

function toggle<T>(list: T[], v: T): T[] {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v];
}

function Chip({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={cn(
        'flex items-start gap-3 rounded-lg border px-3.5 py-3 text-left transition-colors',
        on ? 'border-accent bg-accent/10' : 'border-border bg-surface hover:bg-surface-2',
      )}
    >
      <span className={cn('mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-sm border', on ? 'border-accent bg-accent text-accent-fg' : 'border-border')}>
        {on && <Check size={12} strokeWidth={3} />}
      </span>
      <span className="min-w-0">{children}</span>
    </button>
  );
}

export default function OnboardingPage() {
  useDocumentTitle('Welcome');
  const { user } = useAuth();
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [topicIds, setTopicIds] = useState<number[]>([]);
  const [slugs, setSlugs] = useState<string[]>([]);
  const [follows, setFollows] = useState<string[]>([]);
  const setThemePref = useTheme((s) => s.setPref);
  const [theme, setTheme] = useState<ThemePref>(useTheme.getState().pref);
  const topics = useTopics();
  const suggestions = useOnboardingSuggestions(topicIds, step >= 1);
  const complete = useCompleteOnboarding();
  const skip = useSkipOnboarding();
  const [err, setErr] = useState<string | null>(null);

  const finish = () => {
    setErr(null);
    complete.mutate(
      { topicIds, communitySlugs: slugs, followUsernames: follows, theme },
      {
        onSuccess: () => {
          toast.success('Your feed is ready');
          navigate('/home', { replace: true });
        },
        onError: (e) => setErr(errorMessage(e)),
      },
    );
  };

  return (
    <div className="mx-auto max-w-2xl px-4 py-10">
      <div className="mb-8">
        <p className="text-sm text-muted">Welcome{user ? `, ${displayName(user)}` : ''}</p>
        <h1 className="mt-1 font-serif text-3xl font-semibold tracking-tight">Let’s shape your feed</h1>
        <ol className="mt-6 flex gap-2" aria-label="Progress">
          {STEPS.map((s, i) => (
            <li key={s} className="flex-1">
              <div className={cn('h-1 rounded-full', i <= step ? 'bg-accent' : 'bg-border')} />
              <span className={cn('mt-1.5 block text-xs', i === step ? 'text-text' : 'text-faint')} aria-current={i === step ? 'step' : undefined}>
                {s}
              </span>
            </li>
          ))}
        </ol>
      </div>

      <AnimatePresence mode="wait">
        <motion.section key={step} initial={{ opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -12 }} transition={{ duration: 0.16 }} aria-live="polite">
          {step === 0 && (
            <>
              <h2 className="text-lg font-semibold">What are you interested in?</h2>
              <p className="mb-4 text-sm text-muted">Pick a few topics. We use them to suggest communities and rank your feed.</p>
              {topics.isPending && <div className="grid gap-2 sm:grid-cols-2">{Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-14" />)}</div>}
              {topics.isError && <ErrorState error={topics.error} onRetry={() => topics.refetch()} />}
              {topics.data && (
                <div className="grid gap-2 sm:grid-cols-2">
                  {topics.data.map((t) => (
                    <Chip key={t.id} on={topicIds.includes(t.id)} onClick={() => setTopicIds(toggle(topicIds, t.id))}>
                      <span className="block text-sm font-medium">{t.name}</span>
                      {t.description && <span className="block text-xs text-muted">{t.description}</span>}
                    </Chip>
                  ))}
                </div>
              )}
            </>
          )}

          {step === 1 && (
            <>
              <h2 className="text-lg font-semibold">Join some communities</h2>
              <p className="mb-4 text-sm text-muted">Their posts will appear in your home feed. You can leave any time.</p>
              {suggestions.isPending && <div className="space-y-2">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-14" />)}</div>}
              {suggestions.isError && <ErrorState error={suggestions.error} onRetry={() => suggestions.refetch()} />}
              {suggestions.data && suggestions.data.communities.length === 0 && <p className="text-sm text-muted">No suggestions yet — you can explore communities after setup.</p>}
              <div className="grid gap-2">
                {suggestions.data?.communities.map((c) => (
                  <Chip key={c.id} on={slugs.includes(c.slug)} onClick={() => setSlugs(toggle(slugs, c.slug))}>
                    <span className="flex items-center gap-2">
                      <Avatar src={c.iconUrl} name={c.slug} size="sm" square />
                      <span className="text-sm font-medium">c/{c.slug}</span>
                      <span className="text-xs text-faint">{pluralize(c.memberCount, 'member')}</span>
                    </span>
                    {c.description && <span className="mt-1 line-clamp-2 block text-xs text-muted">{c.description}</span>}
                  </Chip>
                ))}
              </div>
            </>
          )}

          {step === 2 && (
            <>
              <h2 className="text-lg font-semibold">Follow people (optional)</h2>
              <p className="mb-4 text-sm text-muted">Active contributors in the topics you picked.</p>
              {suggestions.data && suggestions.data.users.length === 0 && <p className="text-sm text-muted">No suggestions right now.</p>}
              <div className="grid gap-2 sm:grid-cols-2">
                {suggestions.data?.users.map((u) => (
                  <Chip key={u.id} on={follows.includes(u.username)} onClick={() => setFollows(toggle(follows, u.username))}>
                    <span className="flex items-center gap-2">
                      <Avatar src={u.avatarUrl} name={u.username} size="sm" />
                      <span>
                        <span className="block text-sm font-medium">{displayName(u)}</span>
                        <span className="block text-xs text-faint">u/{u.username}</span>
                      </span>
                    </span>
                  </Chip>
                ))}
              </div>
            </>
          )}

          {step === 3 && (
            <>
              <h2 className="text-lg font-semibold">Choose a theme</h2>
              <p className="mb-4 text-sm text-muted">You can change this any time in Settings.</p>
              <div role="radiogroup" aria-label="Theme" className="grid grid-cols-3 gap-2">
                {([['dark', 'Dark', Moon], ['light', 'Light', Sun], ['system', 'System', Monitor]] as const).map(([v, label, Icon]) => (
                  <button
                    key={v}
                    type="button"
                    role="radio"
                    aria-checked={theme === v}
                    onClick={() => {
                      setTheme(v);
                      setThemePref(v);
                    }}
                    className={cn('flex flex-col items-center gap-2 rounded-lg border px-3 py-4 text-sm', theme === v ? 'border-accent bg-accent/10' : 'border-border hover:bg-surface-2')}
                  >
                    <Icon size={20} /> {label}
                  </button>
                ))}
              </div>
              <div className="mt-6"><FormAlert message={err} /></div>
            </>
          )}
        </motion.section>
      </AnimatePresence>

      <div className="mt-8 flex items-center justify-between gap-3 border-t border-border pt-5">
        <Button
          variant="ghost"
          loading={skip.isPending}
          onClick={() =>
            skip.mutate(undefined, {
              onSuccess: () => navigate('/home'),
              onError: (e) => toast.error(errorMessage(e)),
            })
          }
        >
          Skip for now
        </Button>
        <div className="flex gap-2">
          {step > 0 && <Button variant="outline" onClick={() => setStep((s) => s - 1)}>Back</Button>}
          {step < STEPS.length - 1 ? (
            <Button onClick={() => setStep((s) => s + 1)}>Continue</Button>
          ) : (
            <Button onClick={finish} loading={complete.isPending}>Finish</Button>
          )}
        </div>
      </div>
    </div>
  );
}
