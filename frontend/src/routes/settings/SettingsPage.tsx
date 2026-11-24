import { useSearchParams } from 'react-router-dom';
import { Segmented } from '@/components/ui/Tabs';
import { useAuth } from '@/features/auth/hooks';
import { AccountSettings } from '@/features/users/components/AccountSettings';
import { PreferenceSettings } from '@/features/users/components/PreferenceSettings';
import { ProfileSettings } from '@/features/users/components/ProfileSettings';
import { MutedSettings } from '@/features/mutes/components/MuteControls';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageColumns, PageHeader } from '@/layouts/PageColumns';

type Tab = 'profile' | 'account' | 'preferences' | 'muted';

export default function SettingsPage() {
  useDocumentTitle('Settings');
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'profile';
  if (!user) return null;
  return (
    <PageColumns>
      <PageHeader title="Settings" />
      <div className="mb-4 px-3 sm:px-0">
        <Segmented<Tab>
          label="Settings sections"
          value={tab}
          onChange={(v) => setParams({ tab: v }, { replace: true })}
          options={[
            { value: 'profile', label: 'Profile' },
            { value: 'account', label: 'Account & security' },
            { value: 'preferences', label: 'Preferences' },
            { value: 'muted', label: 'Muted' },
          ]}
        />
      </div>
      <div className="px-4 sm:px-0">
        {tab === 'profile' && <ProfileSettings key={user.id} me={user} />}
        {tab === 'account' && <AccountSettings me={user} />}
        {tab === 'preferences' && <PreferenceSettings me={user} />}
        {tab === 'muted' && <MutedSettings />}
      </div>
    </PageColumns>
  );
}
