import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Textarea } from '@/components/ui/Field';
import { toast } from '@/components/ui/Toast';
import { useCommunity } from '@/features/communities/hooks';
import { ApiError, errorMessage } from '@/services/apiError';
import type { ReportTargetType } from '@/types/moderation';
import { cn } from '@/utils/format';
import { useReport } from '../api';
import { buildReportReasons } from '../helpers';

export interface ReportTarget {
  type: ReportTargetType;
  postId: number;
  commentId?: number;
  communitySlug: string;
  label: string;
}

/** Report a post or comment to the community's moderators, citing a rule or a generic reason. */
export function ReportDialog({ target, open, onOpenChange }: { target: ReportTarget; open: boolean; onOpenChange: (o: boolean) => void }) {
  const community = useCommunity(open ? target.communitySlug : '');
  const reasons = buildReportReasons((community.data?.rules ?? []).map((r) => r.title));
  const [reason, setReason] = useState<string | null>(null);
  const [details, setDetails] = useState('');
  const report = useReport();

  const close = (o: boolean) => {
    onOpenChange(o);
    if (!o) {
      setReason(null);
      setDetails('');
    }
  };

  const submit = () => {
    if (!reason) return;
    report.mutate(
      { targetType: target.type, postId: target.postId, commentId: target.commentId, reason, details: details.trim() || undefined },
      {
        onSuccess: () => {
          toast.success('Thanks, the moderators will review it');
          close(false);
        },
        onError: (e) => {
          if (e instanceof ApiError && e.status === 409) {
            toast.info('You already reported this');
            close(false);
          } else toast.error(errorMessage(e));
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      onOpenChange={close}
      title={target.type === 'POST' ? 'Report post' : 'Report comment'}
      description={target.label}
      footer={
        <>
          <Button variant="ghost" onClick={() => close(false)}>Cancel</Button>
          <Button variant="danger" disabled={!reason} loading={report.isPending} onClick={submit}>Submit report</Button>
        </>
      }
    >
      <fieldset className="space-y-3">
        <legend className="mb-2 text-sm font-semibold">What's wrong with it?</legend>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Report reason">
          {reasons.map((r) => (
            <button
              key={r}
              type="button"
              role="radio"
              aria-checked={reason === r}
              onClick={() => setReason(r)}
              className={cn(
                'rounded-full border px-3 py-1.5 text-left text-xs font-semibold transition-colors',
                reason === r ? 'border-danger bg-danger/15 text-danger' : 'border-border text-text/90 hover:bg-surface-2',
              )}
            >
              {r}
            </button>
          ))}
        </div>
        <label className="block text-xs font-semibold text-muted">
          Details (optional)
          <Textarea value={details} onChange={(e) => setDetails(e.target.value)} rows={3} maxLength={1000} placeholder="Anything the moderators should know" className="mt-1" />
        </label>
        <p className="text-xs text-faint">Reports are anonymous to the person you report. Only moderators see them.</p>
      </fieldset>
    </Dialog>
  );
}
