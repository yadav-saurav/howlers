import { useEffect, useState } from 'react';
import { Button, type ButtonVariant } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Textarea } from '@/components/ui/Field';

/** Confirms an admin action and collects its reason, which goes into the append-only admin audit log. */
export function ActionDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  variant = 'primary',
  reasonRequired,
  loading,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  variant?: ButtonVariant;
  reasonRequired?: boolean;
  loading?: boolean;
  onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');
  useEffect(() => {
    if (open) setReason('');
  }, [open]);
  const missing = !!reasonRequired && !reason.trim();
  return (
    <Dialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      description={description}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant={variant} loading={loading} disabled={missing} onClick={() => onConfirm(reason.trim())}>{confirmLabel}</Button>
        </>
      }
    >
      <label className="mb-1.5 block text-sm font-medium" htmlFor="admin-reason">
        Reason {reasonRequired ? <span className="text-danger">*</span> : <span className="text-xs font-normal text-faint">optional</span>}
      </label>
      <Textarea id="admin-reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={300}
        placeholder="Recorded in the admin audit log" />
    </Dialog>
  );
}
