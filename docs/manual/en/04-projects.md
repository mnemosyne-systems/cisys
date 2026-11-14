\newpage

# Projects

A **project** registers a Git repository with cisys so that it can be built.

![Projects, Admin view](images/projects-admin.png){ width=100% }

## Visibility

* **Public** projects are visible to every signed-in user.
* **Private** projects are visible only to their members and to admins.

Private projects have an **OWNER** member (the creator) and any number of **MEMBER** members. The project's owner, whatever their global role, and admins add and remove members through the membership API; the interface for this is planned, for now it is available at the API (`GET`/`POST`/`DELETE /api/projects/{id}/members[/{username}]`).

The private flag controls visibility of the project and its build logs inside cisys. It does not grant or restrict access to the Git repository itself.

![Projects, Member view](images/projects-member.png){ width=100% }

## Public project page

Every public project has a page at `/<name>/`, for example `http://localhost:5173/cisys/`. It shows the repository branches in a panel on the left and the build history in a table on the right, newest first. Anonymous visitors can read it.

Selecting a branch in the left panel filters the build history to that branch. The page starts on `main`, or `master` for repositories that still use it. Each build row shows a green thumbs up for a passing build, a red thumbs down for a failing build, and a spinner while it runs, followed by the build number, branch, commit, start time, duration and exit code.

Selecting a build opens its own page at `/<name>/<branch>/build/<id>`, for example `http://localhost:5173/cisys/master/build/51`, with the build details and the full log.

Private projects use the same page once you sign in as a member of the project (OWNER or MEMBER) or an administrator; everyone else is told the project does not exist. The branches panel lists the branches that already have builds, because reading a private repository requires credentials.

## Create a project

Leads and admins can create projects. Enter

* **Name**: up to 100 letters, digits, `.`, `_` and `-`, starting with a letter or digit, for example `my-project`. The name is the project's URL, `/<name>/`
* **Repository URL**: an HTTPS URL without embedded credentials, up to 2048 characters
* **Private**: whether the project is private

Projects created before this naming rule keep working. An administrator can rename one in the database; its builds follow, because they reference the project's id:

```sql
UPDATE project SET name = 'new-name' WHERE name = 'old name';
```

The creator's Keycloak subject is stored as the owner, and the creator becomes the project's `OWNER` member in the same transaction; adding or removing members never leaves the project without an owner.

The repository must be on an allowed host and must not resolve to a loopback, private, link-local or metadata address. By default only `github.com` and `gitlab.com` are allowed; the backend settings `cisys.git.allowed-hosts` and `cisys.git.allowed-networks` extend that list.

## Ownership

Ownership uses the immutable Keycloak subject ID, not the user name. A project has exactly one OWNER member — its creator — plus any number of MEMBER members. The membership API cannot assign or remove the OWNER role, and the project's OWNER cannot be removed. Editing the project and pinning its builds require the Lead role and ownership, or the Admin role.

Projects created before project membership existed have no `OWNER` membership, and the schema migration does not add one. After upgrading to this version, the database must be recreated with `make reset`; this deletes the local PostgreSQL and Keycloak data, including all existing projects and builds.
