# Architecture Decision Records — Chat Service

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0000](0000-architecture-foundation.md) | Architecture Foundation — Chat Service | accepted | 2026-07-02 |
| [0001](0001-cache-aside-redis-zset.md) | Cache-Aside Pattern with Redis ZSET for Chat Messages | accepted | 2026-07-02 |
| [0002](0002-jwt-derived-author-identity.md) | JWT-Derived Author Identity for Chat Messages | accepted | 2026-07-02 |
| [0003](0003-cache-staleness-on-redis-restart.md) | Cache Staleness on Redis Restart — TTL + Evict on Reconnect + Evict on Write Failure | accepted | 2026-07-11 |
| [0004](0004-two-layer-chat-authorization.md) | Two-Layer Chat Authorization — PBAC Capability + Resource-State Moderation | accepted | 2026-07-11 |
| [0005](0005-moderation-domain-condition-triggered.md) | Moderation Stays a Chat Kind — `moderation` Domain is Condition-Triggered | accepted | 2026-07-11 |
| [0006](0006-moderation-ux-capability-and-push-split.md) | Chat Moderation UX — Client Capability Signal, Temp-Bans, and Enforcement-Floor / Proactive-Push Split | accepted (Wave 1) | 2026-07-12 |
