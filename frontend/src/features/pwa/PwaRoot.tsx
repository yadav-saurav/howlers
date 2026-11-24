import { usePushSync } from '@/features/push/hooks';
import { UpdatePrompt } from './UpdatePrompt';

/** App-wide PWA pieces: the update prompt and keeping this browser's push subscription current (D-039, D-040). */
export function PwaRoot() {
  usePushSync();
  return <UpdatePrompt />;
}
