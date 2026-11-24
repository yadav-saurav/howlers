import { BellRing, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Display';
import { Switch } from '@/components/ui/Switch';
import { toast } from '@/components/ui/Toast';
import { errorMessage } from '@/services/apiError';
import type { PushCategory, PushDevice } from '@/types/push';
import { useQueryClient } from '@tanstack/react-query';
import { pushKeys, usePushConfig, usePushDevices, useRemoveDevice, useTestPush, useUpdateDevice } from '../hooks';
import { currentSupport, disablePush, enablePush, rememberedDevice } from '../pushClient';
import { CATEGORY_INFO, SUPPORT_MESSAGES } from '../pushLogic';

function relative(iso: string | null) {
  if (!iso) return 'never';
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Push notifications: this device on/off, its categories, a test button and the account's other devices (D-040). */
export function PushSettings({ userId }: { userId: number }) {
  const qc = useQueryClient();
  const config = usePushConfig();
  const support = currentSupport();
  const devices = usePushDevices(!!config.data?.available);
  const update = useUpdateDevice();
  const remove = useRemoveDevice();
  const test = useTestPush();
  const [thisId, setThisId] = useState<number | null>(() => rememberedDevice(userId));
  const [busy, setBusy] = useState(false);
  const [permission, setPermission] = useState(() => (typeof Notification === 'undefined' ? 'default' : Notification.permission));

  const list = devices.data ?? [];
  const thisDevice = list.find((d) => d.id === thisId) ?? null;
  const others = list.filter((d) => d.id !== thisId);

  // The row can disappear server-side (expired subscription, removed from another device): forget it here too.
  useEffect(() => {
    if (devices.isSuccess && thisId != null && !thisDevice) setThisId(null);
  }, [devices.isSuccess, thisId, thisDevice]);

  const refresh = () => qc.invalidateQueries({ queryKey: pushKeys.devices });

  const turnOn = async () => {
    if (!config.data?.publicKey) return;
    setBusy(true);
    try {
      const d = await enablePush(userId, config.data.publicKey);
      setThisId(d.id);
      toast.success('Push notifications are on for this device');
      await refresh();
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setPermission(typeof Notification === 'undefined' ? 'default' : Notification.permission);
      setBusy(false);
    }
  };

  const turnOff = async () => {
    setBusy(true);
    try {
      await disablePush(userId, thisId);
      setThisId(null);
      toast.success('Push notifications are off for this device');
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  const toggleCategory = (d: PushDevice, c: PushCategory, on: boolean) => {
    const next = on ? [...d.categories, c] : d.categories.filter((x) => x !== c);
    update.mutate({ id: d.id, categories: next }, { onError: (e) => toast.error(errorMessage(e)) });
  };

  const sendTest = (id: number) =>
    test.mutate(id, {
      onSuccess: (r) => (r.outcome === 'DELIVERED' ? toast.success(r.message) : toast.error(r.message)),
      onError: (e) => toast.error(errorMessage(e)),
      onSettled: () => void refresh(),
    });

  let body;
  if (config.isLoading) {
    body = <p className="py-3 text-sm text-muted">Checking push support…</p>;
  } else if (config.isError) {
    body = <p className="py-3 text-sm text-muted">Couldn’t load the push settings: {errorMessage(config.error)}</p>;
  } else if (!config.data?.available) {
    body = <p className="py-3 text-sm text-muted">{config.data?.unavailableReason ?? 'Push notifications aren’t available on this server.'}</p>;
  } else {
    body = (
      <>
        {!support.ok ? (
          <p className="py-3 text-sm text-muted">{SUPPORT_MESSAGES[support.reason]}</p>
        ) : (
          <>
            <Switch
              label="Push notifications on this device"
              description={permission === 'denied'
                ? 'Notifications are blocked for this site in your browser. Allow them in the site settings to turn this on.'
                : thisDevice ? `On as “${thisDevice.label}”.` : 'Get replies, mentions and messages even when Howlers is closed.'}
              checked={!!thisDevice}
              disabled={busy || (permission === 'denied' && !thisDevice)}
              onCheckedChange={(v) => void (v ? turnOn() : turnOff())}
            />
            {thisDevice && (
              <div className="border-t border-border pb-3">
                <p className="pt-3 text-xs font-medium uppercase tracking-wide text-faint">Notify this device about</p>
                <div className="divide-y divide-border">
                  {config.data.categories.map((c) => (
                    <Switch
                      key={c}
                      label={CATEGORY_INFO[c].label}
                      description={CATEGORY_INFO[c].description}
                      checked={thisDevice.categories.includes(c)}
                      disabled={update.isPending}
                      onCheckedChange={(v) => toggleCategory(thisDevice, c, v)}
                    />
                  ))}
                </div>
                <Button size="sm" variant="secondary" className="mt-2" disabled={test.isPending} onClick={() => sendTest(thisDevice.id)}>
                  <BellRing size={14} aria-hidden="true" /> Send test notification
                </Button>
              </div>
            )}
          </>
        )}
        {others.length > 0 && (
          <div className="border-t border-border py-3">
            <p className="text-xs font-medium uppercase tracking-wide text-faint">Other devices with push on</p>
            <ul className="mt-2 space-y-2">
              {others.map((d) => (
                <li key={d.id} className="flex items-center justify-between gap-3 text-sm">
                  <span className="min-w-0">
                    <span className="block truncate font-medium">{d.label}</span>
                    <span className="block text-xs text-muted">
                      Added {relative(d.createdAt)} · last push {relative(d.lastUsedAt)}
                    </span>
                  </span>
                  <span className="flex shrink-0 gap-1">
                    <Button size="sm" variant="ghost" disabled={test.isPending} onClick={() => sendTest(d.id)}>Test</Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      aria-label={`Turn off push on ${d.label}`}
                      disabled={remove.isPending}
                      onClick={() => remove.mutate(d.id, { onError: (e) => toast.error(errorMessage(e)) })}
                    >
                      <Trash2 size={14} aria-hidden="true" />
                    </Button>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </>
    );
  }

  return (
    <Card>
      <CardHeader title="Push notifications" />
      <div className="px-4">{body}</div>
    </Card>
  );
}
