# whois-common

> **forked-from:** the legacy predecessor platform (`shared/libs/kotlin/whois-common/`), forked 2026-06-24 (fork Phase 5 Stage 5.0).
> Maintained independently since the fork; do not assume parity with the original.

The shared domain records for the identity user/role directory: `UserRecord`, `UserIdRecord`,
`UserSource`. Pure `@Serializable` data classes with no infrastructure dependencies — shared
between the `infra/identity` service and any client that reads its JSON (notably validate's
optional `WhoisRoleSource`, fork Stage 5.3).

## Package root

`org.tatrman.identity.domain` (renamed off `infra.whois.domain` per the technical-wave package
sweep — fork contracts §1).
