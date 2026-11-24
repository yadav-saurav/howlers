import { PayoutReviews } from '@/features/funding/components/PayoutReviews';
import { StripeStatusPanel } from '@/features/funding/components/StripeStatusPanel';

/** Stripe set-up and the payout review queue (D-047). */
export function PaymentsTab() {
  return (
    <div className="space-y-5">
      <StripeStatusPanel />
      <PayoutReviews />
    </div>
  );
}
