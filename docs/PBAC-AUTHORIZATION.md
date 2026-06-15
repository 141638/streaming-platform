# Policy-Based Authorization (PBAC) — Domain Model & JWT Design

This document defines a **policy-based authorization** model aligned with your control-plane modules (**auth**, **stream**, **chat**, **notification**, **gateway**), plus a **JWT payload** shape that carries enough **authorization facts** for each service to validate requests without synchronous calls back to Auth on every hit (subject to revocation trade-offs in §9).

---

## 1. Design goals

| Goal | Approach |
|------|----------|
| **Consistent language** across services | Shared **resource types** + **actions** naming (catalog below). |
| **PBAC over raw RBAC** | Bind **named policies** to subjects/groups/context; policies reference resource patterns + actions + optional **constraints** (ABAC-lite: owner, room, tier). |
| **Decentralized enforcement** | Each service verifies **JWT signature** + evaluates **claims** against local policy rules for its API surface. |
| **Bounded JWT size** | Prefer **normalized entitlement strings** or **policy version + policy set hash** + optional mini-cache—not full Rego blobs in JWT. |

**PBAC (here)** means: authorization decisions use **explicit policy rules** (“who may do **action** on **resource** under **conditions**”), not only a flat role string. Roles may still exist as **groupings that attach policies**.

---

## 2. Canonical resource types & identifiers

Use a stable **`resource`** string pattern in JWT and logs:

Format: **`{domain}:{kind}:{scope}`**  
Optionally tighten scope: **`{domain}:{kind}:{scope}:{instanceId}`** for instance-bound checks performed in services.

| Domain | Kind | Typical scope / instance | Used by |
|--------|------|---------------------------|---------|
| `identity` | `user` | `self`, `{userSubject}` | auth |
| `identity` | `credential` | `self` | auth |
| `stream` | `session` | `*` or `{sessionId}` | stream |
| `stream` | `publish-key` | `*` or `{sessionId}` | stream, SRS webhook |
| `stream` | `archive` | `*` or `{archiveId}` | stream (future) |
| `media` | `playback` | `room:{externalKey}` or `session:{sessionId}` | gateway/SRS coordination (often public read with rate limits) |
| `chat` | `room` | `*` or `{roomId \| external_key}` | chat |
| `chat` | `message` | `room:{externalKey}` or `{messageId}` | chat |
| `chat` | `moderation` | `room:{externalKey}` | chat |
| `notification` | `subscription` | `self`, `{subscriberSubject}` | notification |
| `notification` | `outbox` | `*` (admin/integration) | notification |
| `platform` | `admin` | `*` | future ops |

**Subject identifiers:** Reuse **`sub`** (JWT) consistently with `broadcaster_subject` / `author_subject` / `subscriber_subject` you already carry as opaque strings (UUID/username issued by Auth).

---

## 3. Action catalog (verbs)

Normalize HTTP + domain actions to these **verbs** (extend over time via policy schema `ver`):

| Verb | Typical meaning |
|------|----------------|
| **create** | Create new aggregate (session, room, subscription, …). |
| **read** | Read metadata or listings (non-sensitive). |
| **read_sensitive** | PII/export-grade reads (narrow use). |
| **update** | Mutate mutable fields. |
| **delete** | Hard/soft delete. |
| **issue_key** | Create/rotate publish credentials for a stream session. |
| **validate_publish** | SRS / internal webhook: validate ingest key/session (trusted caller + policy). |
| **lifecycle** | Start/end stream lifecycle transitions (beyond generic update). |
| **send** | Post chat message in a room. |
| **read_history** | Read durable/historical messages (beyond hot cache TTL). |
| **moderate** | Ban/mute/delete others’ messages, close room features. |
| **subscribe_topics** | Register notification interest (Kafka topic patterns / logical channels). |
| **manage_outbox** | Operator/integration manages notification outbox rows. |
| **impersonate** | Admin-only breakout (dangerous — separate token class). |

**HTTP mapping:** Each route maps to `(resource, action)` in the owning service (see §6).

---

## 4. Policy model (logical)

### 4.1 Policy rule (conceptual)

Each **policy** is a set of **statements**:

- **Effect:** `allow` | `deny` (deny wins if you implement explicit deny).
- **Subjects:** match `sub`, group/role, or attribute (e.g. `claim.tier = PRO`).
- **Resources:** pattern match on §2 strings (e.g. `stream:session:*`).
- **Actions:** subset of §3.
- **Conditions (optional):** `resource.owner == subject.sub`, `time window`, `ip allowlist` (usually edge), `mfa_done`.

### 4.2 Named policies (examples)

| Policy id | Intent |
|-----------|--------|
| `policy.viewer.base` | read playback metadata, read public chat, read notification self. |
| `policy.streamer.live` | create/update own `stream:session`, `issue_key`, `lifecycle` on **own** sessions; `send` in linked chat rooms. |
| `policy.moderator.chat` | `moderate` on assigned rooms (or `*` for staff). |
| `policy.service.srs-webhook` | **service account only:** `validate_publish` on `stream:publish-key` with mTLS / separate `aud`. |
| `policy.admin.platform` | wide `platform:admin:*` (split fine-grained later). |

Store policy definitions in **auth** (or **policy service** later). JWT carries **materialized entitlements** for the current session (§5), not the full policy document.

---

## 5. JWT payload for PBAC

### 5.1 Standard claims (required)

- **`iss`**, **`aud`** (e.g. `streaming-control-plane`), **`sub`**, **`exp`**, **`iat`**, **`jti`**
- Optional: **`nbf`**, **`typ`** (`at+jwt` for access tokens)

### 5.2 PBAC claims (recommended shape)

```json
{
  "iss": "https://auth.example",
  "aud": "streaming-control-plane",
  "sub": "usr_01hxy...",
  "exp": 1735689600,
  "iat": 1735686000,
  "jti": "01hxy...",

  "ver": 1,
  "pv": "2026-02-01T00:00:00Z",

  "ent": [
    "allow stream:session:self create read update lifecycle issue_key",
    "allow stream:publish-key:self validate_publish",
    "allow chat:room:* read",
    "allow chat:message:room:* send",
    "allow notification:subscription:self create read update delete",
    "allow identity:user:self read update"
  ],

  "attr": {
    "roles": ["streamer", "viewer"],
    "tier": "PRO",
    "verified_streamer": true
  }
}
```

**Field meanings**

| Claim | Purpose |
|-------|---------|
| **`ver`** | Entitlement string grammar version (integer). |
| **`pv`** | **Policy version** / materialization timestamp (when entitlements were computed). Rotate when global policies change. |
| **`ent`** | **Compact allow list** (PBAC materialization). Each string: `allow <resourcePattern> <action> [<action>...]`. Use `self` where enforcement resolves instance to `sub` (see §5.3). |
| **`attr`** | Small subject attributes for **local conditions** (not full user profile). |

**Why `ent` strings vs JSON objects:** Smaller, diffable, log-friendly. Parser is trivial in each service.

**Alternative when JWT must stay tiny:**  
`ent_hash` + `pv` + Auth **introspection** or **Redis policy snapshot** (adds latency / coupling). Prefer **short `ent`** + **short TTL** (e.g. 5–15 min) + **refresh** for long sessions.

### 5.3 Resolving `self` and ownership

For resources like `stream:session:self`:

- Service loads row by id from path; checks **`row.broadcaster_subject == sub`** (or mapped internal user id).
- Deny if mismatch even if token contains `stream:session:*` unless a separate **admin** entitlement exists.

This combines **PBAC in token** with **resource-level ABAC** in the service (recommended for streaming/chat).

### 5.4 Service-to-service (SRS webhook, internal jobs)

Do **not** reuse end-user JWT for SRS. Use:

- **`sub`:** `svc:srs-webhook` (or workload identity)
- **`aud`:** `stream-service-internal`
- **`ent`:** narrow, e.g. `allow stream:publish-key:* validate_publish`
- **Transport:** mTLS or HMAC + IP allowlist + replay protection

---

## 6. Enforcement matrix (by module / rough business logic)

Map each API or future route to **`(resource, action)`**. Services reject if no **allow** matches after pattern + condition evaluation.

### 6.1 auth-service (`/api/auth`)

| Operation / flow | Resource | Action |
|------------------|----------|--------|
| Register / create account | `identity:user` | `create` (often public or invite-only) |
| Login / token issue | `identity:user` | `read` (credential check is pre-auth) |
| Change password / profile | `identity:user:self` | `update` |
| Admin user search | `identity:user:*` | `read_sensitive` (admin policy) |

### 6.2 stream-service (`/api/streams`)

| Operation / flow | Resource | Action |
|------------------|----------|--------|
| Health / public metadata | `stream:session` | `read` (if exposed) |
| Create stream session / key | `stream:session:self` | `create`, `issue_key` |
| Update session / end stream | `stream:session:{id}` | `update`, `lifecycle` (owner or admin) |
| List my sessions | `stream:session:self` | `read` |
| SRS webhook validate key | `stream:publish-key:{sessionOrKeyRef}` | `validate_publish` (**service token**) |
| Archive metadata write | `stream:archive:{id}` | `create` / `update` (owner / system) |

### 6.3 chat-service (`/api/chat`)

| Operation / flow | Resource | Action |
|------------------|----------|--------|
| Read recent / hot path | `chat:room:{key}` | `read` |
| Read historical page | `chat:message:room:{key}` | `read_history` |
| Post message | `chat:message:room:{key}` | `send` |
| Moderation actions | `chat:moderation:room:{key}` | `moderate` |
| Create room (if explicit) | `chat:room` | `create` (often bound to stream lifecycle) |

**Linking room to stream:** Enforce “only streamer or viewers of that stream can X” via **room metadata** (e.g. `external_key = streamId`) + stream-service read (sync) or **embedded claim** `attr.viewable_rooms` (can blow JWT size—prefer service lookup for fan-out).

### 6.4 notification-service (`/api/notifications`)

| Operation / flow | Resource | Action |
|------------------|----------|--------|
| Manage my subscriptions | `notification:subscription:self` | `create`, `read`, `update`, `delete` |
| Operator / integration outbox | `notification:outbox:*` | `manage_outbox` (narrow service/admin) |
| Kafka consumer | N/A (no user JWT) | workload identity + topic ACLs |

### 6.5 gateway

- **TLS termination**, **JWT validation** (optional at edge), **rate limits**, **route-level** coarse checks (e.g. require `Bearer`).  
- **Fine PBAC** remains in domain services to avoid duplicating all rules in gateway config.

---

## 7. Evaluation algorithm (each service)

1. **Authenticate:** Verify JWT crypto (`iss`, signature, `exp`, optional `aud`).  
2. **Authorize:** Parse `ver`, build **request context**: `(sub, attr, HTTP method/path, extracted ids)`.  
3. **Map route → (resource, action)** via static table §6.  
4. **Expand `ent`:** For each entitlement line `allow <pattern> <actions...>`: if pattern matches resource AND action listed → **allow**.  
5. **Apply deny + ownership:** Explicit deny wins; then **instance checks** (owner, room membership).  
6. **Audit:** Log `sub`, `jti`, `policy pv`, decision (allow/deny), resource id (redact PII).

---

## 8. Persistence sketch (Auth / policy store)

Suggested tables in **`auth`** schema (names indicative):

| Table | Role |
|-------|------|
| `policy` | Stable `policy_id`, version, serialized rules (YAML/JSON) or Rego bundle ref. |
| `policy_attachment` | Map `policy_id` → `principal` (`user`, `group`, `role`, `svc_account`). |
| `entitlement_materialization_cache` | Optional: `sub`, `pv`, `ent` blob, `expires_at` for issuance path. |

**Issuance:** On login / refresh / policy change webhook, Auth computes **`ent`** for `sub` + `pv` and puts into JWT access token.

---

## 9. Trade-offs & hardening checklist

| Topic | Recommendation |
|-------|----------------|
| **Revocation** | Short `exp`, refresh tokens server-side revocation list, or `jti` blocklist in Redis for critical events. |
| **JWT size** | Cap `ent` lines; push rare permissions to **on-demand** check for admin paths only. |
| **Privacy** | Avoid PII in `attr`; avoid listing all room ids in JWT. |
| **Cross-service consistency** | Single **policy version** `pv` broadcast via config or event when global policies change; force re-login or refresh. |
| **Chat “who can see room”** | Prefer **server-side membership** or **stream entitlements** over huge JWT claims. |

---

## 10. Optional diagram (request path)

```mermaid
sequenceDiagram
    participant C as Client
    participant G as Gateway
    participant S as Domain service
    participant A as Auth (issue only)

    C->>G: HTTPS + Bearer JWT
    G->>S: Forward Authorization
    S->>S: Verify JWT + evaluate ent + ownership
    Note over S: No sync call to Auth on happy path
    A-->>C: Earlier: login returns access JWT with ent
```

---

## 11. Next implementation steps (non-binding)

1. Fix **route → (resource, action)** tables in code (Spring Security `AuthorizationManager` or custom filters).  
2. Implement **Auth token issuer** with `ent` materialization from DB policies.  
3. Add **SRS webhook** service token path separate from user JWT.  
4. Add **policy version** `pv` to monitoring and force **token refresh** on policy publish.

This document is the contract for **PBAC + JWT** across your microservices; adjust `ent` grammar in lockstep with `ver` when you extend actions or resource patterns.
