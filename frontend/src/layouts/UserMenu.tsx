import { Bookmark, LogOut, Moon, Settings, ShieldHalf, Sun, User } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Avatar } from '@/components/ui/Display';
import { Menu, MenuContent, MenuItem, MenuLabel, MenuSeparator, MenuTrigger } from '@/components/ui/Menu';
import { toast } from '@/components/ui/Toast';
import { useAuth, useLogout } from '@/features/auth/hooks';
import { useUpdateSettings } from '@/features/users/hooks';
import { resolveTheme, useTheme } from '@/hooks/useTheme';
import { compactNumber, displayName } from '@/utils/format';

export function UserMenu() {
  const { user } = useAuth();
  const logout = useLogout();
  const navigate = useNavigate();
  const pref = useTheme((s) => s.pref);
  const setPref = useTheme((s) => s.setPref);
  const save = useUpdateSettings();
  if (!user) return null;
  const dark = resolveTheme(pref) === 'dark';
  return (
    <Menu>
      <MenuTrigger
        aria-label={`Account menu, ${user.username}`}
        className="inline-flex h-9 items-center gap-2 rounded-md px-1.5 hover:bg-surface-2"
      >
        <Avatar src={user.avatarUrl} name={user.username} size="sm" />
        <span className="hidden text-sm font-medium xl:inline">{user.username}</span>
      </MenuTrigger>
      <MenuContent>
        <MenuLabel>
          <span className="block text-sm font-medium text-text">{displayName(user)}</span>
          <span>{compactNumber(user.postKarma + user.commentKarma)} karma</span>
        </MenuLabel>
        <MenuSeparator />
        <MenuItem icon={<User />} onSelect={() => navigate(`/u/${user.username}`)}>Profile</MenuItem>
        <MenuItem icon={<Bookmark />} onSelect={() => navigate('/saved')}>Saved</MenuItem>
        <MenuItem icon={<Settings />} onSelect={() => navigate('/settings')}>Settings</MenuItem>
        {user.role === 'ADMIN' && <MenuItem icon={<ShieldHalf />} onSelect={() => navigate('/admin')}>Admin dashboard</MenuItem>}
        <MenuItem
          icon={dark ? <Sun /> : <Moon />}
          onSelect={() => {
            const next = dark ? 'light' : 'dark';
            setPref(next);
            save.mutate({ theme: next });
          }}
        >
          {dark ? 'Light theme' : 'Dark theme'}
        </MenuItem>
        <MenuSeparator />
        <MenuItem
          icon={<LogOut />}
          onSelect={() =>
            logout.mutate(undefined, {
              onSettled: () => {
                toast.info('Signed out');
                navigate('/');
              },
            })
          }
        >
          Log out
        </MenuItem>
      </MenuContent>
    </Menu>
  );
}
