# Next milestones

1. Finish the authentication slice. Done: Keycloak login, `member`/`lead`/`admin` realm roles, subject-based project ownership, role and private-project authorization tests, and configurable branding, and a Flyway baseline migration with Hibernate schema validation. Remaining: integration tests against a real Keycloak and server configuration.
2. Repository access. Done: JGit fetch of public HTTPS repositories on `github.com` and `gitlab.com`, private access through environment-variable credential references (admins only), the commit SHA recorded per build, including after a force push, and project membership with per-project roles (`OWNER` and `MEMBER`). Remaining: private credentials stored using a secrets provider, and per-repository permissions.
3. Manual builds. Done: a single in-process runner using Docker or Podman with no network, CPU/memory/PID limits, a timeout and stored logs; builds left running by a restart are marked failed. Persistent FIFO queue with stored request details, atomic claims, configurable concurrency and queue limits, and restart recovery. Remaining: cancellation, container cleanup after a crash, and a separately isolated Linux worker using disposable rootless containers with a restricted network and no container-engine socket or host credentials exposed. Define these before enabling untrusted builds.
4. Multi-user deployment: TLS, secure Keycloak settings and secrets, database backups, audit records, access control, rate limits, concurrency controls and tested upgrade/migration procedures.
5. CI configuration, webhooks, artifacts and additional worker platforms.

Do not use the current starter for production or execute untrusted builds until the isolation model has been reviewed and tested.
