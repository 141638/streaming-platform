/**
 * Deterministic avatar + author-identity helpers shared by the chat surfaces.
 * Extracted from {@code ChatPanelComponent} so the moderation UI renders the
 * same identity treatment without duplication.
 */

/**
 * Build a DiceBear avatar URL from a seed string.
 * Deterministic — the same seed always produces the same avatar.
 */
export function dicebearAvatarUrl(seed: string): string {
  return `https://api.dicebear.com/9.x/thumbs/svg?seed=${encodeURIComponent(seed)}`;
}

/** Truncate a UUID-style sub to a shorter display-safe label. */
export function truncateSub(sub: string): string {
  return sub.length > 12 ? sub.substring(0, 8) + '…' : sub;
}

/** Display name: prefer the username, fall back to the truncated subject. */
export function displayName(subject: string, username: string | null): string {
  return username ?? truncateSub(subject);
}
