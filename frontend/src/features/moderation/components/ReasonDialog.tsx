import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Textarea } from '@/components/ui/Field';

/** Asks a moderator for an (optional) reason before a removal; the reason is shown to the author and logged. */
export function ReasonDialog({
  open,
  onOpenChange,
  title,
  confirmLabel,
  loading,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  confirmLabel: string;
  loading?: boolean;
  onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description="The reason is shared with the author and recorded in the moderation log."
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="danger" loading={loading} onClick={() => onConfirm(reason.trim())}>{confirmLabel}</Button>
        </>
      }
    >
      <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={300} placeholder="e.g. Breaks rule 2: no low-effort help requests" aria-label="Reason" />
    </Dialog>
  );
}
