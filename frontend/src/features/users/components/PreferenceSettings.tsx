import { Monitor, Moon, Sun } from 'lucide-react';
import { Card, CardHeader } from '@/components/ui/Display';
import { Select } from '@/components/ui/Field';
import { Switch } from '@/components/ui/Switch';
import { toast } from '@/components/ui/Toast';
import { useTheme, type ThemePref } from '@/hooks/useTheme';
import { errorMessage } from '@/services/apiError';
import type { Me, UserSettings } from '@/types/api';
import { cn } from '@/utils/format';
import { useUpdateSettings } from '../hooks';
import { ModeratorRankingSetting } from '@/features/reputation/components/ModeratorRankingSetting';

const THEMES: { value: ThemePref; label: string; icon: typeof Sun }[] = [
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'system', label: 'System', icon: Monitor },
];

export function PreferenceSettings({ me }: { me: Me }) {
  const update = useUpdateSettings();
  const setPref = useTheme((s) => s.setPref);
  const save = (patch: Partial<UserSettings>) =>
    update.mutate(patch, { onSuccess: () => toast.success('Preferences saved'), onError: (e) => toast.error(errorMessage(e)) });
  const s = me.settings;
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Appearance" />
        <div role="radiogroup" aria-label="Theme" className="grid grid-cols-3 gap-2 p-4">
          {THEMES.map(({ value, label, icon: Icon }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={s.theme === value}
              onClick={() => {
                setPref(value);
                save({ theme: value });
              }}
              className={cn('flex flex-col items-center gap-1.5 rounded-md border px-3 py-3 text-sm transition-colors', s.theme === value ? 'border-accent bg-accent/10' : 'border-border hover:bg-surface-2')}
            >
              <Icon size={18} /> {label}
            </button>
          ))}
        </div>
      </Card>
      <Card>
        <CardHeader title="Privacy & content" />
        <div className="divide-y divide-border px-4">
          <Switch label="Public profile" description="When off, only your username and avatar are visible to others." checked={s.profilePublic} disabled={update.isPending} onCheckedChange={(v) => save({ profilePublic: v })} />
          <Switch label="Show my communities" description="List the communities you’ve joined on your profile." checked={s.showCommunities} disabled={update.isPending} onCheckedChange={(v) => save({ showCommunities: v })} />
          <Switch label="Show mature content" description="Include posts marked as mature in feeds and search." checked={s.showNsfw} disabled={update.isPending} onCheckedChange={(v) => save({ showNsfw: v })} />
        </div>
      </Card>
      <Card>
        <CardHeader title="Messages" />
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="min-w-0">
            <label htmlFor="dm-policy" className="text-sm font-medium">Who can message you</label>
            <p className="text-xs text-muted">People you’ve blocked can never message you, whatever you choose here.</p>
          </div>
          <Select
            id="dm-policy"
            className="w-auto min-w-[200px]"
            value={s.dmPolicy ?? 'EVERYONE'}
            disabled={update.isPending}
            onChange={(e) => save({ dmPolicy: e.target.value as NonNullable<UserSettings['dmPolicy']> })}
          >
            <option value="EVERYONE">Everyone</option>
            <option value="FOLLOWING">Only people I follow</option>
            <option value="NOBODY">Nobody</option>
          </Select>
        </div>
      </Card>
      <ModeratorRankingSetting />
      <Card>
        <CardHeader title="Notifications" />
        <div className="px-4">
          <Switch label="Email notifications" description="Receive a digest of replies and mentions by email." checked={s.emailNotifications} disabled={update.isPending} onCheckedChange={(v) => save({ emailNotifications: v })} />
        </div>
      </Card>
    </div>
  );
}
