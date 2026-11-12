/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.StreamingOutput;
import java.time.Instant;
import java.util.List;

/**
 * The page of a single project, addressed by its name (for example /cisys/ in the UI).
 * Public projects are readable by anyone, including visitors who are not signed in.
 * Private projects are only readable by their owner and admins; everyone else gets a 404,
 * so the existence of a private project is not revealed.
 */
@Path("/api/project-pages/{name}")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class ProjectPageResource {
    @Inject ProjectAccess access;
    @Inject BuildLogs logs;

    public record ProjectInfo(Long id, String name, String repositoryUrl, boolean privateRepository) {}
    /** Leaves out who requested the build and any credential reference, as the page may be anonymous. */
    public record PageBuild(Long id, Long projectId, Build.Status status, String revision, String branch, String image,
                            String command, Instant startedAt, Instant finishedAt, String imageDigest, Integer exitCode,
                            boolean logTruncated, boolean pinned, boolean canPin) {}
    public record ProjectPage(ProjectInfo project, List<PageBuild> builds) {}

    @GET
    public ProjectPage page(@PathParam("name") String name, @QueryParam("branch") String branch) {
        Project p = access.visible(name);
        String branchFilter = branch == null ? "" : branch.trim();
        List<Build> rows = branchFilter.isEmpty()
                ? Build.<Build>find("projectId = ?1 order by id desc", p.id).page(0, 50).list()
                : Build.<Build>find("projectId = ?1 and branch = ?2 order by id desc", p.id, branchFilter).page(0, 50).list();
        // Whoever may see a private project may also pin its builds.
        boolean canPin = access.canSeePrivate(p);
        List<PageBuild> builds = rows.stream().map(b -> pageBuild(b, canPin)).toList();
        return new ProjectPage(new ProjectInfo(p.id, p.name, p.repositoryUrl, p.privateRepository), builds);
    }

    /** One build of a project, for the dedicated build page. Visibility matches the project page and the log. */
    @GET
    @Path("/builds/{buildId}")
    public PageBuild build(@PathParam("name") String name, @PathParam("buildId") Long buildId) {
        Project p = access.visible(name);
        Build b = Build.findById(buildId);
        if (b == null || !p.id.equals(b.projectId)) throw new NotFoundException();
        return pageBuild(b, access.canSeePrivate(p));
    }

    private static PageBuild pageBuild(Build b, boolean canPin) {
        return new PageBuild(b.id, b.projectId, b.status, b.revision, b.branch, b.image, b.command, b.startedAt,
                b.finishedAt, b.imageDigest, b.exitCode, b.logTruncated, b.pinned, canPin);
    }

    @GET
    @Path("/builds/{buildId}/log")
    @Produces("text/plain; charset=UTF-8")
    public StreamingOutput log(@PathParam("name") String name, @PathParam("buildId") Long buildId) {
        Project p = access.visible(name);
        Build b = Build.findById(buildId);
        if (b == null || !p.id.equals(b.projectId)) throw new NotFoundException();
        return out -> logs.streamTo(buildId, out);
    }
}
