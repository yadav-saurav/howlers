import { Moon, Sun } from 'lucide-react';
import { IconButton } from '@/components/ui/Button';
import { useAuth } from '@/features/auth/hooks';
import { useUpdateSettings } from '@/features/users/hooks';
import { resolveTheme, useTheme } from '@/hooks/useTheme';

/** Toggles dark/light. When signed in, the preference is also persisted server-side. */
export function ThemeToggle() {
  const pref = useTheme((s) => s.pref);
  const setPref = useTheme((s) => s.setPref);
  const { isAuthed } = useAuth();
  const save = useUpdateSettings();
  const isDark = resolveTheme(pref) === 'dark';
  const next = isDark ? 'light' : 'dark';
  return (
    <IconButton
      label={`Switch to ${next} theme`}
      size="sm"
      onClick={() => {
        setPref(next);
        if (isAuthed) save.mutate({ theme: next });
      }}
    >
      {isDark ? <Sun size={17} /> : <Moon size={17} />}
    </IconButton>
  );
}
