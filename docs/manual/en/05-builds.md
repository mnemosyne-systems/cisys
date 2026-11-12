\newpage

# Builds

A **build** fetches a branch of a project's repository and runs a command inside a disposable container on the backend host.

![Manual build configuration](images/builds.png){ width=100% }

## Start a build

Leads and admins open **Build history** on a visible project and start a manual build by entering

* **Branch**: the branch to fetch
* **Image**: one of the images an admin has allowed on the **Images** page
* **Command**: the shell command to run
* **Credential reference** (admins only, optional): see below

Members can view build history and logs for the projects they can access. Private projects and their logs are visible only to their creator and admins.

## How a build runs

1. The branch is fetched afresh with JGit at depth 1, so a force push is reflected in the next build.
2. The commit ID is recorded.
3. The checkout, without its `.git` directory, is copied into a container created without networking, with all capabilities dropped, `no-new-privileges`, a PID limit of 256, 1 GiB of memory and one CPU, running as the unprivileged user `65534:65534`.
4. The command runs in a writable copy of the checkout under `/tmp/build`.
5. The status is `PASSED` when the exit code is 0, otherwise `FAILED`. A build that exceeds ten minutes is killed and reported as timed out.

Build requests are stored as `QUEUED` and claimed in request order. By default one build runs at a time. Set `BUILD_CONCURRENCY` (`cisys.build.concurrency`) to change the number of concurrent builds and `BUILD_QUEUE_LIMIT` (`cisys.build.queue-limit`, default 100) to limit waiting requests. Both limits must be positive. A full queue returns HTTP 429 with a clear error. Requests keep their repository URL, branch, image, command and credential reference; credentials themselves are read from the environment when execution begins.

If saving a completed result fails, the runner retains it in memory and retries on each dispatcher tick without rerunning the build. Its slot stays reserved until the result is saved.

On restart, interrupted `RUNNING` builds become `FAILED` and retain their partial logs. `QUEUED` builds remain queued and run when a slot is available. Queued and running builds are protected from retention cleanup and prevent project deletion. This runner supports one backend instance; startup recovery assumes the previous instance has stopped.

## What a build records

Each build stores the requested branch, image reference and command, the credential reference name if one was used (shown to admins only), the image digest when the container runtime reports one, the commit ID, the container exit code (none when the build timed out or never ran), who started it, and when it started and finished; the duration is derived from these times.

## Build logs

The complete output of the build, standard output and standard error together, is stored in PostgreSQL as one large object per build, appended while the build runs. It is stored as the raw bytes the build wrote and shown as UTF-8. Output from a running build is visible within about five seconds; use **Refresh** on the build.

* `BUILD_LOG_MAX_BYTES` (default 1 GiB) is a safety limit per build, not a normal cap. Output beyond it is read and discarded, a marker line is added, and the build is marked as truncated. The limit is exact in bytes, so if it falls inside a multi-byte character, the last character shown may be incomplete.
* A build is also marked as truncated if the log could not be stored completely. Logs from before this version were capped at 120 KB; those that reached the cap are marked as truncated.
* The build page shows the first 2 MiB of a log. **Download log** saves the full log; in Chromium-based browsers it is written straight to the chosen file, in other browsers it is held in browser memory first. The backend never holds a whole log in memory.

## Pinned builds

The project's creator and admins can **Pin** a build on its build page, and **Unpin** it again. A pinned build, with its log, is kept permanently; build retention cleanup skips it. Pinned builds are marked with a pin icon in the build history.

## Build retention

Every hour, and a minute after startup, cisys deletes old builds of each project together with their logs. A finished, unpinned build is deleted when either

* it finished more than the maximum age ago (default 30 days), or
* the project has more newer finished, unpinned builds than the maximum count (default 50).

Admins change both limits under **Settings** → **Retention**; `0` turns a limit off. Running and pinned builds are never deleted, and pinned builds do not count towards the limit. Deleted builds cannot be restored, so pin a build to keep it. `BUILD_RETENTION_EVERY` (default `1h`) changes how often the cleanup runs.

## Repository URLs

Only `github.com` and `gitlab.com` HTTPS URLs are accepted for builds. Redirects, embedded credentials, query strings, fragments and non-default ports are rejected.

## Private Git repositories

Set an environment variable whose name starts with `CISYS_GIT_`, for example `CISYS_GIT_PROJECT`, to a read-only provider token in the backend environment before starting it. Then, as an admin, enter that variable name in the build form.

* The value is never stored in the project database and is not passed to the build container.
* Only admins can use credential references until credentials are scoped to individual projects.
* Never include secrets in build commands or output.

## Images

Admins manage build images under **Images** in the header. The page lists the images present in the container runtime on the backend host, with their ID, size and age.

* **Pull** downloads an image from its registry, for example `docker.io/library/alpine:3.23`.
* **Remove** deletes a local image and takes it off the allowed list.
* Tick the images that builds may use and select **Save**. Only those images appear in the build form, and the backend rejects any other image.

An image that is allowed but no longer present locally is listed separately, where it can be pulled again or cleared.

## Container runtime

The runner is always on. It uses Podman when it is installed and Docker otherwise.

**Trust boundary:** the runner allows trusted leads and admins to execute code through the local container daemon. Docker socket access grants host-level control to the backend. These limits are not a hostile-code sandbox. Use a dedicated machine, disk quotas and trusted repositories, and do not expose this setup to the Internet. Repository checkout sizes are not bounded.

## Verification status

The build path was verified with Docker on Linux, including a public repository fetched with JGit and an Alpine container. It has not been verified end-to-end with a real private Git provider, Podman, or Windows/WSL.
