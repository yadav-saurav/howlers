import { brand } from '@/design-system/brand';
import { StaticPage } from './StaticPage';

export default function PrivacyPage() {
  return (
    <StaticPage title="Privacy Policy" updated="November 2026">
      <p>This is a working draft describing how the current development build handles data.</p>
      <h2>What we store</h2>
      <ul>
        <li><strong>Account data:</strong> username, email, a salted password hash (never the password itself), profile fields you choose to add.</li>
        <li><strong>Content:</strong> posts, comments, votes, saves and community memberships.</li>
        <li><strong>Sessions:</strong> for each signed-in device we store a hashed refresh token, the user agent and IP address so you can review and revoke sessions in Settings.</li>
      </ul>
      <h2>How we use it</h2>
      <p>To run the service: authenticate you, personalise your feed from the communities and people you follow, send notifications, and keep the platform safe.</p>
      <h2>What we don’t do</h2>
      <ul>
        <li>We don’t sell personal data.</li>
        <li>We don’t show your email address publicly.</li>
        <li>Your saved posts and votes are private to you.</li>
      </ul>
      <h2>Your controls</h2>
      <p>
        You can make your profile private, hide your community list, revoke sessions, deactivate your account, or delete it.
        Deleting anonymizes your account; your past posts remain as “[deleted]” so discussions stay readable.
      </p>
      <h2>Cookies</h2>
      <p>{brand.name} uses a single essential, HTTP-only cookie to keep you signed in. There are no advertising cookies.</p>
    </StaticPage>
  );
}
