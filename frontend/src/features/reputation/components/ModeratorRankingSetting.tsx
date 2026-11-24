import { Card, CardHeader } from '@/components/ui/Display';
import { Switch } from '@/components/ui/Switch';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import { useModeratorSettings, useUpdateModeratorSettings } from '../api';

/** Settings card: moderators can keep their score private (off every leaderboard and hidden on their profile). */
export function ModeratorRankingSetting() {
  const q = useModeratorSettings();
  const update = useUpdateModeratorSettings();
  return (
    <Card>
      <CardHeader title="Moderator recognition" />
      <div className="px-4">
        <Switch
          label="Show me on moderator leaderboards"
          description="When off, your moderator score is still computed but only you can see it, on your profile."
          checked={q.data?.publicRanking ?? true}
          disabled={q.isPending || update.isPending}
          onCheckedChange={(v) =>
            update.mutate({ publicRanking: v }, { onSuccess: () => toast.success('Preference saved'), onError: (e) => toast.error(errorMessage(e)) })
          }
        />
      </div>
    </Card>
  );
}
