# keycloak-scim-provisioning

A Keycloak **Event Listener SPI** extension that pushes user/group provisioning changes to your
SCIM server, using the Captain-P-Goldfish `scim-sdk-client` library — the same SDK family as your
`scim-sdk-server` implementation. This makes Keycloak a second SCIM-provisioning source alongside
Azure AD.

## Operations covered

| Keycloak action                                   | Trigger                          | SCIM call                          |
|----------------------------------------------------|-----------------------------------|-------------------------------------|
| Create user (admin console / Admin REST / self-register) | AdminEvent / Event               | `POST /Users`                      |
| Update user (profile, enable/disable, name, email)  | AdminEvent / Event (UPDATE_PROFILE) | `PUT /Users/{id}`                |
| Delete user                                         | `UserModel.UserRemovedEvent`      | `DELETE /Users/{id}`               |
| Create group                                        | AdminEvent                       | `POST /Groups`                     |
| Update group (rename)                               | AdminEvent                       | `PATCH /Groups/{id}` (displayName only) |
| Delete group                                        | `GroupModel.GroupRemovedEvent`   | `DELETE /Groups/{id}`              |
| User joins group                                    | AdminEvent (GROUP_MEMBERSHIP, CREATE) | `PATCH /Groups/{id}` (add member) |
| User leaves group                                   | AdminEvent (GROUP_MEMBERSHIP, DELETE) | `PATCH /Groups/{id}` (remove member) |

Realm role / client role mappings are **not** mapped to SCIM groups in this phase — add that in
`ScimEventListenerProvider` if you need it (map `ResourceType.REALM_ROLE_MAPPING` similarly to
`GROUP_MEMBERSHIP`).

## Why DELETE is handled differently

Keycloak's `AdminEvent` for a DELETE operation fires *after* the entity is gone, with no
representation — there's nothing to read the SCIM external ID from. Instead this extension
registers `KeycloakSessionFactory` model-event listeners (`UserModel.UserRemovedEvent`,
`GroupModel.GroupRemovedEvent`) in `postInit()`. These fire synchronously, inside the same
transaction as the removal, while the entity is still readable, so the `scimExternalId` attribute
can be captured before it disappears. Only the actual outbound HTTP DELETE is dispatched
asynchronously — reading the attribute itself is synchronous and cheap.

## Correlation / external ID tracking

Each Keycloak user/group gets a single attribute (default name `scimExternalId`, configurable)
holding the SCIM server's assigned resource ID once first synced. Its presence is what
distinguishes "needs POST" from "needs PUT/PATCH".

## Build

Versions are pinned to what you're running elsewhere: Keycloak 26.6.3, `scim-sdk-client` 1.32.0
(matching your `scim-sdk-server` version), JDK 21. Update `keycloak.version`/`scim.sdk.version` in
`pom.xml` if either changes later.

```bash
mvn clean package
```

This produces `target/keycloak-scim-provisioning.jar`, shaded with the SCIM-SDK client and its
HTTP client dependencies (Keycloak's own SPI/core jars stay `provided` and are not shaded, to
avoid classpath conflicts with what the server already provides).

## Deploy

Copy the jar into Keycloak's providers directory and rebuild:

```bash
cp target/keycloak-scim-provisioning.jar $KEYCLOAK_HOME/providers/
$KEYCLOAK_HOME/bin/kc.sh build
```

## Configure

Via `keycloak.conf` / CLI (preferred):

```
spi-events-listener-scim-provisioning-scim-base-url=https://your-scim-server.example.com/scim/v2
spi-events-listener-scim-provisioning-auth-mode=BEARER
spi-events-listener-scim-provisioning-bearer-token=${env.SCIM_BEARER_TOKEN}
spi-events-listener-scim-provisioning-async-threads=2
```

Or via env vars as a fallback: `SCIM_BASE_URL`, `SCIM_AUTH_MODE` (`NONE`/`BASIC`/`BEARER`),
`SCIM_BASIC_USERNAME`/`SCIM_BASIC_PASSWORD`, `SCIM_BEARER_TOKEN`, `SCIM_EXTERNAL_ID_ATTRIBUTE`,
`SCIM_ASYNC_THREADS`, `SCIM_CONNECT_TIMEOUT`, `SCIM_REQUEST_TIMEOUT`, `SCIM_SKIP_TLS_VERIFY`.

Then enable the listener on the realm: Admin Console → Realm Settings → Events → Event Listeners
→ add `scim-provisioning`. **Also enable "Save events" for admin events with "include
representation" on**, or AdminEvent payloads may not carry what's needed.

## Admin console UI (optional, experimental)

There's now a **"SCIM Provisioning" tab under Realm Settings** (`ScimConfigTab`) where you can set
the SCIM base URL, auth mode, credentials, and external-ID attribute *per realm*, instead of only
via `keycloak.conf`/env vars. Anything left blank in the UI falls back to the server-level default.
Saving the form invalidates that realm's cached SCIM client so the next event picks up the new
config immediately.

**This requires the experimental `declarative-ui` feature flag:**
```
kc.sh start --features=declarative-ui
```
(or add `features=declarative-ui` to `keycloak.conf`). Without it, the tab won't appear at all, and
the extension just falls back to the server-level `ScimProvisioningConfig` for every realm — no
event-processing behavior depends on the UI feature being enabled.

Do **not** treat this UI piece with the same confidence as the rest of the extension — it's built
on `org.keycloak.services.ui.extend.UiTabProvider`, which is a Keycloak preview/experimental SPI,
not a stable public API. I confirmed its exact shape (`onCreate`, `getConfigProperties()`,
`getPath()`/`getParams()`) against Keycloak's own `extend-admin-console-spi` quickstart source, but
I could not verify how the admin console pre-fills the form with previously-saved values from that
single example — check that round-trips correctly in your Keycloak version before relying on it for
more than a dev/test setup. If it doesn't, `keycloak.conf`/`KC_SPI_*` env vars remain the reliable
fallback path (see above) and the extension works identically either way.

Also worth deciding deliberately: realm attributes aren't encrypted at rest, so the bearer
token/basic-auth password saved through this tab sit in the DB in plain text, visible via the
realm's REST/export representation like any other attribute. If that's not acceptable for your
threat model, swap `ScimConfigTab`/`ScimClientHolder` to store a vault alias instead and resolve the
actual secret through Keycloak's vault SPI.

## API corrections confirmed by actual compiler errors

Two method/package names in `ScimClientHolder` were wrong and have been corrected based on real
compile errors against the actual `scim-sdk-client` 1.32.0 jar (more reliable than my earlier
guesses from the wiki, which apparently described a different version's shape):

- `BasicAuth` lives in `de.captaingoldfish.scim.sdk.client.http`, not `...client.auth`.
- The builder method is `configBuilder.httpClientBuilder(...)`, not `.httpClient(...)`.

This is very likely the actual root cause of the earlier silent stall at `here3.0.8.1`/`3.0.8.2`:
if `mvn package` picked up a different `scim-sdk-client`/`scim-sdk-common` version than what your
IDE indexed (or vice versa), a call to a method that doesn't exist at runtime on the actual loaded
jar throws `NoSuchMethodError` — which, combined with the swallowed-Throwable bug fixed earlier,
would explain exactly what you saw: no compile error, no logged exception, execution just stops.

Worth still running once to be sure there's only one version now:
```cmd
mvn dependency:tree | findstr scim-sdk
```

## Known issue fixed: removing a user from a group (and un-nesting a child group) did nothing

Two related bugs, both about removal:

1. **User removed from a group was silently skipped entirely.** The top-level
   `onEvent(AdminEvent...)` had `if (operationType == DELETE) return;` checked *before* looking at
   `resourceType` at all. That was meant to skip whole-entity deletion (a user or group being
   deleted outright — correctly handled elsewhere via `UserModel.UserRemovedEvent`/
   `GroupModel.GroupRemovedEvent`), but it also caught **`GROUP_MEMBERSHIP` DELETE** — a user being
   removed from a group without the user or group itself being deleted. Fixed by moving the
   DELETE-skip to only apply once we know we're looking at a plain `users/{id}` or `groups/{id}`
   path; `GROUP_MEMBERSHIP` and the `groups/{id}/children` path now run through for both CREATE and
   DELETE operation types.

2. **Un-nesting a child group (moving it away from a parent) wasn't tracked at all** —
   `syncGroupChildren` previously only ever added current children, with no way to notice one had
   disappeared (there's no separate event fired on the child itself when it leaves a parent; only
   the affected parent's `groups/{id}/children` path fires). Fixed by having each parent group
   record which child IDs it last saw as a Keycloak group attribute
   (`scim.lastKnownChildGroupIds`), diffing that against the current live subgroups on every sync,
   and issuing `removeGroupChildMember` for anything that dropped out — using only APIs already
   confirmed working elsewhere (attribute get/set, PATCH-based add/remove), rather than the
   unverified SCIM list/filter API used in the 409-reconciliation fix.

## Nested/child groups: create and move now fully supported

(Originally added as an ADD-only fix; the removal/un-nesting gap noted here has since been closed
— see the "removing a user from a group" section above for the follow-up fix.) Confirmed from real
logs: moving a group to become a child of another (or creating a new child group) fires
`resourceType=GROUP`, `resourcePath=groups/{parentId}/children`, `operationType=UPDATE` — a shape
the original regex (`^groups/([^/]+)$`) never matched, so these events were silently falling
through unhandled. Added detection for this path plus `syncGroupChildren(...)`, which ensures the
parent and each current child are synced, adds each new child as a `Group`-typed member on the
parent's SCIM group (RFC 7643's standard mechanism for representing nested groups), and removes any
child that's no longer present.

## Known issue fixed: a lost create response could orphan a user forever

Diagnosed from a real repeated 409 in testing: `createUser` POSTs, the SCIM server actually creates
the user and returns success, but the response is lost client-side (network hiccup, or a paused
debugger blowing past the request timeout). Our own retry logic then resends the POST, which
correctly 409s since the user now really exists — but `createUser()` still returned `null` overall,
so the Keycloak user never got its `scimExternalId` recorded. Every future sync for that user then
kept hitting the same 409 forever, since Keycloak had no record of the SCIM side already having it.

Fixed by reconciling on 409: `createUser` now looks the existing resource up by `userName` and
adopts its id instead of giving up. This is self-healing for existing orphaned records too — you
should **not** need to manually clean up already-affected users on the SCIM server; the next sync
attempt for them will 409, look them up, and adopt the existing id automatically.

**Caveat:** the lookup (`findUserIdByUserName`) uses a list/filter query
(`scimRequestBuilder.list(User.class, ...).filter("userName eq \"...\"").get()...`) whose exact
method/type shape I could not verify against your installed `scim-sdk-client` 1.32.0 the way I did
for create/update/delete/patch earlier — this is the least-confident part of this change. If it
doesn't compile, it's almost certainly just a method/type rename, same as the `BasicAuth`
package/`httpClientBuilder` fixes earlier.

## Known issue fixed: group updates were wiping group membership

`ScimGroupMapper.toScimGroup(GroupModel)` never populated a `members` field — membership was always
meant to be synced separately via the dedicated `addMember`/`removeMember` PATCH calls. But group
*updates* (e.g. a simple rename) went through `updateGroup(...)`, which issues a SCIM **PUT** — and
per RFC 7644, PUT is a full-resource replace. Since the mapped `Group` object never carried
members, every PUT sent an empty/absent `members` field, which a compliant SCIM server correctly
interprets as "clear membership." Fixed by adding `ScimSyncService.renameGroup(...)`, a PATCH that
touches only `displayName`, and switching the event listener's update path to use it instead of the
full PUT. `updateGroup` (PUT) is still available in `ScimSyncService` for cases where you actually
want a full resource replace with a fully-populated `Group` object — just don't use it for anything
partial. The same PUT-is-a-full-replace caveat is worth keeping in mind if you ever extend
`ScimUserMapper`/`updateUser` with additional fields later — anything the mapper doesn't populate
gets cleared on PUT, not left alone.

## Known issue fixed: UI-saved config didn't survive a server restart

Confirmed by testing: a value saved through the SCIM Provisioning tab worked immediately, but
reverted to the server-level (`keycloak.conf`/env var) config after restarting Keycloak. Root
cause: `realm.setAttribute(...)` was being called directly on the `RealmModel` instance handed to
`onCreate`/`onUpdate` by the declarative-UI framework. That write only ever landed in Keycloak's
runtime cache — it looked correct within the same server run, but was never actually committed to
the database, so it vanished the moment the cache rebuilt from the DB on restart. Fixed by writing
the attribute inside its own explicit `KeycloakModelUtils.runJobInTransaction(...)` job — opening a
fresh session/transaction, re-fetching the realm by ID, setting the attribute there, and letting
that job's own commit flush it for real — rather than trusting whatever transaction state the
framework's callback session happens to be in.

## Known issue fixed: edits made through the admin console UI were silently discarded

If the SCIM Provisioning tab kept behaving as though it saved nothing — always falling back to the
server-level `keycloak.conf`/env-var config no matter what you entered — this was why.
`UiTabProviderFactory` extends Keycloak's `ComponentFactory`, which has separate `onCreate` and
`onUpdate` lifecycle methods, and **`onUpdate`'s default implementation is a no-op**. Keycloak
auto-creates an empty `ComponentModel` for the tab the first time it's rendered (firing `onCreate`
with blank values) — so by the time an admin actually fills in the form and hits Save, that's an
**update** to an already-existing component, not a create. Without overriding `onUpdate`, every
real edit made through the UI was silently thrown away. Fixed by implementing `onUpdate` with the
same persistence logic as `onCreate` (see `ScimConfigTab.persist(...)`).

## Known issue fixed: moving a group to top-level did nothing (different event shape than moving into a parent)

Confirmed from real logs: moving a group *into* a parent fires `groups/{parentId}/children`, but
moving a group *out* to top-level fires a bare `groups/` path with **no group ID anywhere in it** —
a shape nothing was matching, so these events were silently ignored. Since that event carries no ID
to say which group changed, `syncTopLevelGroupReconciliation(...)` instead sweeps every top-level
group and checks a new group attribute, `LAST_KNOWN_PARENT_ATTR`, which each group now maintains on
itself recording which parent it last belonged to; anything found still pointing at an old parent
gets removed from that parent's SCIM membership and the attribute cleared.

This also uncovered a related gap: moving a group directly between two *existing* parents might
only ever fire an event on the *new* parent, never the old one. `syncGroupChildren` now also checks
this same child-side `LAST_KNOWN_PARENT_ATTR` whenever it processes a newly-appeared child, so the
old parent gets cleaned up as a side effect of handling the new parent's event — regardless of
whether Keycloak ever fires anything for the old parent directly. This complements (doesn't
replace) the existing parent-side `LAST_KNOWN_CHILDREN_ATTR` diffing, which still catches the case
where the same parent's own `/children` event fires again later.

**Unverified:** `realm.getTopLevelGroupsStream()` — assumed present on `RealmModel` following the
same Stream-suffix convention confirmed for `GroupModel.getSubGroupsStream()`, but not specifically
checked against 26.6.4.

## Known issue fixed: deleting a child group left a stale membership reference on its parent forever

Confirmed from real testing: delete a child group that was previously synced under a parent, and
the parent's SCIM record kept a permanent stale "Group" member entry pointing at the now-deleted
child — because by the time `syncGroupChildren`'s async diff logic ran, the child no longer existed
in Keycloak to look its SCIM id up from (logged as "Could not resolve SCIM id for removed child
group ... its stale membership may need manual cleanup"). This corrupted tracking state on the
parent could also interfere with syncing *other* children added afterward. Fixed by cleaning up the
parent's SCIM membership reference (and the parent's `LAST_KNOWN_CHILDREN_ATTR` tracking) directly
inside the `GroupModel.GroupRemovedEvent` handler, synchronously, while the about-to-be-deleted
group's `getParentId()` is still readable — the same technique already used for reading the
deleted group's own `scimExternalId` before it disappears.

**Caveat:** treating `getParentId() == realm.getId()` as "this is a top-level group, no parent to
clean up" is an assumption — Keycloak has used different conventions across versions for what a
top-level group's parent reference actually is. Worth confirming this doesn't skip cleanup for a
real parent, or misfire for a genuinely top-level group.

## Known issue fixed: SDK's own error-parser could throw and mask the real failure reason

Confirmed via a real stack trace: `handle()` calls `response.getErrorResponse()` to log a clean
error message, but that call itself can throw (`IncompatibleAttributeException: operation not
possible for array`) if the SCIM server's error response body doesn't conform to the shape the SDK
expects for a standard SCIM error (this server's custom `BusinessException`-style error bodies seem
to be the trigger). That exception was propagating out of `handle()` and being logged as if it
*were* the failure, hiding whatever the server's actual rejection reason was. Fixed by wrapping the
`getErrorResponse()` call and falling back to the raw HTTP status/response body when the SDK can't
parse it — so the next time this happens, the log will show the server's real error content instead
of an unrelated parser exception.

## Known issue fixed: real errors were being silently swallowed

If you were seeing a job seemingly stop mid-execution with no exception logged anywhere, this was
why. Two compounding issues:

1. `executor.submit(...)`'s returned `Future` was discarded. Any `Throwable` thrown inside that
   task gets stored on the `Future` and is never logged or surfaced unless something explicitly
   calls `.get()` on it.
2. The inner `catch (Exception e)` blocks didn't catch `Error` subclasses at all —
   `NoSuchMethodError` / `NoClassDefFoundError` (typically caused by a version mismatch between
   `scim-sdk-client` and `scim-sdk-common`, or any other classpath conflict) extend `Error`, not
   `Exception`, so they passed straight through the catch block and then vanished per point 1.

Fixed by catching `Throwable` instead of `Exception` in every background job (both the
create/update path in `ScimEventListenerProvider` and the delete path in
`ScimEventListenerProviderFactory`). If a job now dies for a classpath/version reason, you'll see
the real `NoSuchMethodError`/`NoClassDefFoundError` in the logs instead of silence. If you were
debugging a "hang" that turned out to have no exception at all in the logs even after this fix,
check `mvn dependency:tree | grep scim-sdk` for more than one version of `scim-sdk-client` /
`scim-sdk-common` on the classpath — that mismatch is the most likely cause of a swallowed `Error`
at exactly the `ScimClientConfig.builder()...` call chain.

## Known issue fixed: user/group lookup returning null in the async job

Earlier versions of this extension submitted straight to the background executor from inside
`onEvent(...)`. That races the surrounding admin request's transaction commit: `AdminEvent` fires
*before* commit, so a background job opening its own fresh session (via `runJobInTransaction`)
could run before the new user/group row is actually visible in the database, and
`getUserById`/`getGroupById` would spuriously return `null` even though the entity really was
created. Fixed by deferring the `executor.submit(...)` call itself until after this session's
transaction commits, via `KeycloakTransactionManager.enlistAfterCompletion(...)` — see
`ScimEventListenerProvider.afterCommit(...)`. That hook runs synchronously right after commit
(cheap — it's just enqueuing a task), so it adds negligible latency to the admin REST response;
only the outbound SCIM HTTP call itself happens later, on the background executor.

## Things worth verifying before you trust this in anything beyond a dev/test integration

1. **Bearer auth wiring** (`ScimClientHolder`): the SCIM-SDK client wiki only documents
   `basicAuth(...)` and client-cert auth directly on `ScimClientConfig`; bearer token support here
   is implemented via a preconfigured Apache `HttpClientBuilder` interceptor, based on release
   notes mentioning that `ScimClientConfig` accepts preconfigured http-client-builders. Check your
   exact `scim-sdk-client` version's `ScimClientConfig` for a dedicated bearer method before
   relying on this — if one exists, prefer it and drop the interceptor.
2. **`UserModel.UserRemovedEvent` / `GroupModel.GroupRemovedEvent`** are internal Keycloak model
   events, not public SPI contracts — their shape has been stable across recent major versions but
   isn't guaranteed the way `EventListenerProviderFactory` is. Confirm against the Keycloak version
   you're actually running (check `org.keycloak.models.UserModel` / `GroupModel` source for that
   version) before deploying to something you care about.
3. **Retry policy** in `ScimSyncService` is a simple fixed-backoff retry (3 attempts). If your SCIM
   server has a JWKS mismatch or auth hiccup like the one you're working around for the Keycloak/
   Azure AD dual-IDP setup, failed calls will retry 3x and then just log an error — there's no
   dead-letter/outbox table here. If you need guaranteed delivery across Keycloak restarts, that's
   the next thing to add (a small outbox table + scheduled retry job, similar in spirit to the
   delta-sync pattern from your Security Server Migration Module).
4. **Admin console UI tab** — see the "Admin console UI" section above; this is the least-verified
   part of the whole extension since it relies on an experimental Keycloak SPI I could only check
   against one reference example.
5. **Group membership PATCH `remove` filter syntax** (`members[value eq "..."]`) follows RFC7644,
   but confirm your SCIM server's PATCH filter-path handling accepts it exactly this way — you
   already know from your `UserResourceHandler` work that PATCH filter-path parsing has server-side
   nuances.
