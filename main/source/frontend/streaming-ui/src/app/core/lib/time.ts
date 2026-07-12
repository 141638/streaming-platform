/**
 * Small, dependency-free time helpers for the moderation surfaces.
 * Kept pure (no Date.now() inside) so callers pass an explicit {@code nowMs}
 * tick — this makes the countdown deterministic and trivially unit-testable.
 */

const MINUTE_MS = 60_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;
const WEEK_MS = 604_800_000;
const MONTH_MS = 2_592_000_000; // 30 days
const YEAR_MS = 31_536_000_000; // 365 days

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

/**
 * Human-readable relative time from {@code nowMs} to an ISO-8601 timestamp.
 *
 * Returns {@code "Now"} for timestamps within 45 seconds,
 * {@code "5 minutes ago"} / {@code "2 hours ago"} / {@code "1 day ago"} /
 * {@code "3 weeks ago"} / {@code "2 months ago"} / {@code "1 year ago"}.
 *
 * Future timestamps are formatted the same way but with "from now".
 * Unparseable input returns the raw string unchanged.
 */
export function formatRelativeTime(isoTimestamp: string, nowMs: number): string {
  const thenMs = Date.parse(isoTimestamp);
  if (Number.isNaN(thenMs)) {
    return isoTimestamp;
  }

  const diff = nowMs - thenMs;
  const abs = Math.abs(diff);
  const suffix = diff >= 0 ? 'ago' : 'from now';

  // For the "just now" threshold we use 45 seconds to match common convention.
  if (abs < 45_000) {
    return 'Now';
  }

  const value = formatRelativeValue(abs);
  return `${value} ${suffix}`;
}

/** Pick the largest whole unit for {@code ms} and format as "N unit(s)". */
function formatRelativeValue(ms: number): string {
  if (ms < MINUTE_MS) {
    const n = Math.floor(ms / 1_000);
    return `${n} second${n === 1 ? '' : 's'}`;
  }
  if (ms < HOUR_MS) {
    const n = Math.floor(ms / MINUTE_MS);
    return `${n} minute${n === 1 ? '' : 's'}`;
  }
  if (ms < DAY_MS) {
    const n = Math.floor(ms / HOUR_MS);
    return `${n} hour${n === 1 ? '' : 's'}`;
  }
  if (ms < WEEK_MS) {
    const n = Math.floor(ms / DAY_MS);
    return `${n} day${n === 1 ? '' : 's'}`;
  }
  if (ms < MONTH_MS) {
    const n = Math.floor(ms / WEEK_MS);
    return `${n} week${n === 1 ? '' : 's'}`;
  }
  if (ms < YEAR_MS) {
    const n = Math.floor(ms / MONTH_MS);
    return `${n} month${n === 1 ? '' : 's'}`;
  }
  const n = Math.floor(ms / YEAR_MS);
  return `${n} year${n === 1 ? '' : 's'}`;
}
