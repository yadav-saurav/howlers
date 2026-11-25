import { useRetirePush } from '@/features/push/hooks';
import { UpdatePrompt } from './UpdatePrompt';

/** App-wide PWA pieces: the update prompt, and switching off push for browsers that enabled it earlier. */
export function PwaRoot() {
  useRetirePush();
  return <UpdatePrompt />;
}
