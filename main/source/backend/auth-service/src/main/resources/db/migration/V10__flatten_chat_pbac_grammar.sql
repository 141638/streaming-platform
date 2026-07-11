-- Flatten the chat entitlement grammar to the canonical 3-segment form
-- {domain}:{kind}:{scope} so the downstream chat-service EntitlementMatcher
-- (ported from stream-service) resolves scope correctly, and close two
-- policy-coverage gaps. See docs/adr/chat/0004-two-layer-chat-authorization.md
-- (grammar risk resolved via "flatten the seed") and docs/adr/chat/0005 for the
-- condition-triggered moderation-domain alternative.
--
-- Why a forward migration UPDATE (not an edit to V3): V3 is already applied and
-- its live rows have drifted (streamer gained chat:moderation:room:self by hand).
-- This migration re-asserts an authoritative target state for the three chat
-- policies, reconciling any drift. Full definitions are rewritten so non-chat
-- statements (stream / identity / notification / media) are preserved verbatim.
--
-- Grammar changes (4-segment "room:" qualifier dropped; scope is now self/*/uuid):
--   chat:message:room:*    -> chat:message:*
--   chat:moderation:room:* -> chat:moderation:*
--   chat:moderation:room:self -> chat:moderation:self
--
-- Coverage / consolidation changes:
--   * viewers may now `send` and `read_history` (chat:message:*), matching the
--     product expectation that authenticated viewers post and scroll history.
--   * streamers self-moderate their own room (chat:moderation:self moderate).
--   * message reads unify under chat:message:* `read` (recent) + `read_history`
--     (durable page); chat:room:* `read` is retained for room-metadata lookups.
--
-- version stays 1: issuance re-materializes ent lines from the current enabled
-- rows on every login, so an in-place UPDATE is sufficient and attachments are
-- untouched. In-flight tokens carrying old 4-segment lines simply fail to match
-- until refresh; enforcement ships dark (chat.pbac.enabled=false) so this is inert
-- until deliberately enabled after a token-refresh window.

UPDATE auth.policy
SET definition = $json$
{"statements":[
  {"effect":"allow","resource":"media:playback:*","actions":["read"]},
  {"effect":"allow","resource":"chat:room:*","actions":["read"]},
  {"effect":"allow","resource":"chat:message:*","actions":["read","send","read_history"]},
  {"effect":"allow","resource":"notification:subscription:self","actions":["create","read","update","delete"]},
  {"effect":"allow","resource":"identity:user:self","actions":["read"]}
]}
$json$
WHERE policy_key = 'policy.viewer.base' AND version = 1;

UPDATE auth.policy
SET definition = $json$
{"statements":[
  {"effect":"allow","resource":"stream:session:self","actions":["create","read","update","lifecycle","issue_key"]},
  {"effect":"allow","resource":"stream:publish-key:self","actions":["validate_publish"]},
  {"effect":"allow","resource":"chat:room:*","actions":["read"]},
  {"effect":"allow","resource":"chat:message:*","actions":["read","send","read_history"]},
  {"effect":"allow","resource":"chat:moderation:self","actions":["moderate"]},
  {"effect":"allow","resource":"identity:user:self","actions":["read","update"]}
]}
$json$
WHERE policy_key = 'policy.streamer.live' AND version = 1;

UPDATE auth.policy
SET definition = $json$
{"statements":[
  {"effect":"allow","resource":"chat:moderation:*","actions":["moderate"]},
  {"effect":"allow","resource":"chat:message:*","actions":["read","read_history","send","delete"]},
  {"effect":"allow","resource":"chat:room:*","actions":["read"]}
]}
$json$
WHERE policy_key = 'policy.moderator.chat' AND version = 1;
