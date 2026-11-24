import { Pup, type PupPose } from './Pups';
import './mascot.css';

export type MascotPose = PupPose;

/**
 * A wolf pup for empty, error and waiting states. Purely decorative (the SVG is aria-hidden), theme-aware through the
 * --pup-* tokens in mascot.css, and it only idles (breathing, blinking, tail wag) when motion is allowed.
 */
export function Mascot({ pose = 'sit', size = 96, className = '' }: { pose?: MascotPose; size?: number; className?: string }) {
  return (
    <span
      className={`mascot mascot-idle inline-flex shrink-0 ${className}`}
      style={{ width: size, height: Math.round(size * (124 / 120)) }}
      aria-hidden="true"
      data-mascot={pose}
    >
      <Pup pose={pose} fur="theme" className="h-full w-full" />
    </span>
  );
}
