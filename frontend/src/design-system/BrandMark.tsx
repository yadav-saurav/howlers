import { HowlersMark } from './HowlersMark';

/** The app's logo mark (the Howlers pup), optionally with the wordmark. Kept as BrandMark so call sites stay stable. */
export function BrandMark({ size, withName = false }: { size?: number; withName?: boolean }) {
  return <HowlersMark size={size} withName={withName} />;
}
