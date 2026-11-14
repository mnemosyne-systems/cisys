# Entity diagram

The entities are defined in `src/backend/main/java/ai/mnemosyne/cisys`. The schema is managed by Flyway migrations in `src/backend/main/resources/db/migration`, and Hibernate validates the entities against it (`quarkus.hibernate-orm.schema-management.strategy=validate`).

```mermaid
erDiagram
    PROJECT {
        BIGINT id PK
        STRING name
        STRING repository_url
        BOOLEAN private_repository
        STRING owner_subject
    }

    BUILD {
        BIGINT id PK
        BIGINT project_id
        STRING requested_by
        STRING status
        STRING revision
        STRING branch
        STRING image
        STRING workflow
        STRING image_digest
        INTEGER exit_code
        BOOLEAN log_truncated
        BOOLEAN pinned
        OID log_oid
        TIMESTAMP started_at
        TIMESTAMP finished_at
    }

    BRANDING {
        BIGINT id PK
        STRING name
        STRING header_color
        STRING heading_color
        STRING button_color
        TEXT logo
        TEXT background
    }

    SETTINGS {
        BIGINT id PK
        INTEGER retention_days
        INTEGER retention_max_builds
        INTEGER max_concurrent_builds
        INTEGER max_queued_builds
    }

    PROJECT_MEMBERSHIP {
        BIGINT id PK
        BIGINT project_id
        STRING subject
        STRING role
        TIMESTAMP created_at
    }

    PROJECT ||--o{ BUILD : has
    PROJECT ||--o{ PROJECT_MEMBERSHIP : has
```

* `PROJECT.owner_subject` and `BUILD.requested_by` hold the Keycloak subject (`sub`) of the user. `PROJECT_MEMBERSHIP.subject` holds the same kind of subject; exactly one row per project has role `OWNER` — the creator, recorded atomically with project creation.
* `PROJECT_MEMBERSHIP.project_id` is a foreign key to `PROJECT` with `ON DELETE CASCADE`; a unique index over `(project_id, subject)` and a partial unique index on `project_id` for `OWNER` rows enforce one membership per user per project and one OWNER per project.
* `OWNER` rows are never assignable through the membership API and one must always exist; there is no ownership-transfer path.
* `BUILD.project_id` is a plain column, not a foreign key constraint.
* `BUILD.logoid` (type `lo`, from the `lo` extension) references the build log, stored as one PostgreSQL large object. It is not mapped in the `Build` entity. The `lo_manage` trigger unlinks the large object when the build is deleted, so no logs are orphaned.
* `BUILD.status` is `QUEUED`, `RUNNING`, `PASSED` or `FAILED`.
* `BRANDING` and `SETTINGS` are singleton rows with `id = 1`. Until `SETTINGS` is first saved, its defaults apply.
* Users, roles and credentials live in Keycloak and are not stored in the cisys database.
