import {
  Bookmark,
  Compass,
  Flame,
  Home,
  MessagesSquare,
  Trophy,
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
  { to: '/saved', label: 'Saved', icon: Bookmark, auth: true },
  { to: '/messages', label: 'Messages', icon: MessagesSquare, auth: true },
  { to: '/leaderboard', label: 'Leaderboard', icon: Trophy },
];

/** Features scheduled for later phases. They route to a single honest "coming soon" page. */
export const upcomingNav: NavItem[] = [
];
