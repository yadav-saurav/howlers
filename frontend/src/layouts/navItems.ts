import {
  Bookmark,
  Compass,
  Flame,
  FolderLock,
  Globe,
  Headphones,
  Home,
  LibraryBig,
  MessagesSquare,
  Sparkles,
  Trophy,
  HandCoins,
  type LucideIcon,
} from 'lucide-react';

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  auth?: boolean;
}

export const primaryNav: NavItem[] = [
  { to: '/home', label: 'Home', icon: Home },
  { to: '/home?tab=popular', label: 'Popular', icon: Flame },
  { to: '/explore', label: 'Explore', icon: Compass },
  { to: '/home?tab=latest', label: 'All', icon: Globe },
  { to: '/saved', label: 'Saved', icon: Bookmark, auth: true },
  { to: '/messages', label: 'Messages', icon: MessagesSquare, auth: true },
  { to: '/library', label: 'Library', icon: LibraryBig, auth: true },
  { to: '/ai', label: 'Community AI', icon: Sparkles, auth: true },
  { to: '/podcasts', label: 'Podcasts', icon: Headphones },
  { to: '/funding', label: 'Moderator funding', icon: HandCoins, auth: true },
  { to: '/files', label: 'Files', icon: FolderLock, auth: true },
  { to: '/leaderboard', label: 'Leaderboard', icon: Trophy },
];

/** Features scheduled for later phases. They route to a single honest "coming soon" page. */
export const upcomingNav: NavItem[] = [
];
