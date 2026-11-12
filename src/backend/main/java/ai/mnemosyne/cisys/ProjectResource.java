/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.security.Authenticated;
import org.eclipse.microprofile.jwt.JsonWebToken;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import jakarta.persistence.PersistenceException;

@Path("/api") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProjectResource {
    @Inject SecurityIdentity identity;
    @Inject RepositoryUrlValidator repositoryUrls;
    private JsonWebToken token() {
        if (identity.getPrincipal() instanceof JsonWebToken jwt) return jwt;
        throw new NotAuthorizedException("Bearer");
    }
    private String subject() {
        String sub = token().getSubject();
        if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
        return sub;
    }
    private void requireCisysRole() {
        if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
    }
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    static final String NAME_RULE = "Project names use letters, digits, '.', '_' and '-', and start with a letter or digit";
    public record CreateProject(String name, String repositoryUrl, boolean privateRepository) {}
    /** The owner's subject is for admins only; everyone else gets {@code owned}. */
    public record ProjectResponse(Long id, String name, String repositoryUrl, boolean privateRepository, boolean owned,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String ownerSubject) {}
    private ProjectResponse response(Project p) {
        return new ProjectResponse(p.id, p.name, p.repositoryUrl, p.privateRepository, subject().equals(p.ownerSubject),
                identity.hasRole("admin") ? p.ownerSubject : null);
    }
    @GET @Path("/session") public Map<String,Object> session() {
        requireCisysRole();
        return Map.of("username", token().getClaim("preferred_username") == null ? subject() : token().getClaim("preferred_username"), "subject", subject(), "roles", identity.getRoles());
    }
    @GET @Path("/projects") public List<ProjectResponse> list() {
        requireCisysRole();
        List<Project> projects = identity.hasRole("admin") ? Project.listAll()
                : Project.list("privateRepository = false or ownerSubject = ?1", subject());
        return projects.stream().map(this::response).toList();
    }
    @POST @Path("/projects") @Transactional public Response create(CreateProject input) {
        if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 100 || input.repositoryUrl() == null || input.repositoryUrl().length() > 2048) throw new BadRequestException("Name and repository URL are required");
        String name = input.name().trim();
        // The name is the project's URL path, for example /cisys/ in the UI.
        if (!NAME.matcher(name).matches()) throw badRequest(NAME_RULE);
        String url;
        try { url = gitUrl(input.repositoryUrl().trim()); }
        catch (WebApplicationException e) { throw e; }
        catch (IllegalArgumentException e) { throw new BadRequestException(RepositoryUrls.SHAPE); }
        try { repositoryUrls.validate(url); } catch (IllegalArgumentException e) { throw badRequest(e.getMessage()); }
        // Checked across all projects, including private projects the caller cannot see.
        if (Project.count("lower(name) = ?1", name.toLowerCase(Locale.ROOT)) > 0) throw conflict("A project with this name already exists");
        if (Project.count("repositoryUrl", url) > 0) throw conflict("A project with this repository URL already exists");
        Project p = new Project(); p.name = name; p.repositoryUrl = url; p.privateRepository = input.privateRepository(); p.ownerSubject = subject();
        try { p.persistAndFlush(); }
        catch (PersistenceException e) { throw conflict("A project with this name or repository URL already exists"); }
        return Response.status(201).entity(response(p)).build();
    }
    /** Deletes a project and its builds; the database unlinks their logs. Admins, or the lead who owns it. */
    @DELETE @Path("/projects/{id}") @Transactional public Response delete(@PathParam("id") Long id) {
        if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
        BuildRunner.lockQueue();
        Project p = Project.findById(id);
        boolean admin = identity.hasRole("admin");
        boolean owner = p != null && subject().equals(p.ownerSubject);
        // Private projects the caller cannot see answer 404, as everywhere else.
        if (p == null || (p.privateRepository && !admin && !owner)) throw new NotFoundException();
        if (!admin && !owner) throw new ForbiddenException();
        if (Build.count("projectId = ?1 and status in ('RUNNING', 'QUEUED')", id) > 0) throw conflict("A build is queued or running. Delete the project when its builds finish.");
        Build.delete("projectId", id);
        p.delete();
        return Response.noContent().build();
    }
    /** Repository URLs name the Git repository itself: https://github.com/org/repo becomes https://github.com/org/repo.git. */
    static String gitUrl(String url) {
        String trimmed = url.replaceAll("/+$", "");
        URI parsed = URI.create(trimmed);
        String path = parsed.getPath();
        if (parsed.getQuery() != null || parsed.getFragment() != null || path == null || path.isEmpty() || path.endsWith("/.git"))
            throw badRequest("Use the repository's URL, for example https://github.com/org/repo.git");
        return path.endsWith(".git") ? trimmed : trimmed + ".git";
    }
    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(Response.status(400).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build());
    }
    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(409).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build());
    }
}
