/**
 * Small, dependency-free time helpers for the moderation surfaces.
 * Kept pure (no Date.now() inside) so callers pass an explicit {@code nowMs}
 * tick — this makes the countdown deterministic and trivially unit-testable.
 */

const MINUTE_MS = 60_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

/**
 * Human-readable countdown from {@code nowMs} to a ban's expiry.
 *
 * Returns {@code "expired"} once the instant has passed (or the input is
 * unparseable), otherwise the largest whole unit — e.g. {@code "expires in 42m"},
 * {@code "expires in 3h"}, {@code "expires in 2d"}.
 */
export function formatExpiresIn(expiresAtIso: string, nowMs: number): string {
  const expiryMs = Date.parse(expiresAtIso);
  if (Number.isNaN(expiryMs)) {
    return 'expired';
  }

  const remaining = expiryMs - nowMs;
  if (remaining <= 0) {
    return 'expired';
  }

  if (remaining >= DAY_MS) {
    return `expires in ${Math.floor(remaining / DAY_MS)}d`;
  }
  if (remaining >= HOUR_MS) {
    return `expires in ${Math.floor(remaining / HOUR_MS)}h`;
  }

  // Floor to whole minutes, but never show "0m" while still active.
  const minutes = Math.max(1, Math.floor(remaining / MINUTE_MS));
  return `expires in ${minutes}m`;
}
