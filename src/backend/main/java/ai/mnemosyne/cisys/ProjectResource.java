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
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.hibernate.exception.ConstraintViolationException;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
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
    if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin")))
      throw new ForbiddenException();
  }

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
  static final String NAME_RULE =
      "Project names use letters, digits, '.', '_' and '-', and start with a letter or digit";

  public record CreateProject(
      String name, String repositoryUrl, boolean privateRepository, String credentialRef) {}

  public record UpdateProject(
      String name, String repositoryUrl, boolean privateRepository, String credentialRef) {}

  public record Credential(String credentialRef) {}

  /**
   * The owner's subject and the credential reference are for admins only; everyone else gets {@code
   * owned} and no credential, so a GET response can be sent back unchanged.
   */
  public record ProjectResponse(
      Long id,
      String name,
      String repositoryUrl,
      boolean privateRepository,
      boolean owned,
      @JsonInclude(JsonInclude.Include.NON_NULL) String ownerSubject,
      String credentialRef) {}

  private ProjectResponse response(Project p) {
    return new ProjectResponse(
        p.id,
        p.name,
        p.repositoryUrl,
        p.privateRepository,
        subject().equals(p.ownerSubject),
        identity.hasRole("admin") ? p.ownerSubject : null,
        identity.hasRole("admin") ? p.credentialRef : null);
  }

  @GET
  @Path("/session")
  public Map<String, Object> session() {
    requireCisysRole();
    return Map.of(
        "username",
        token().getClaim("preferred_username") == null
            ? subject()
            : token().getClaim("preferred_username"),
        "subject",
        subject(),
        "roles",
        identity.getRoles());
  }

  @GET
  @Path("/projects")
  public List<ProjectResponse> list() {
    requireCisysRole();
    List<Project> projects =
        identity.hasRole("admin")
            ? Project.listAll()
            : Project.list("privateRepository = false or ownerSubject = ?1", subject());
    return projects.stream().map(this::response).toList();
  }

  @POST
  @Path("/projects")
  @Transactional
  public Response create(CreateProject input) {
    if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
    if (input == null
        || input.name() == null
        || input.name().isBlank()
        || input.name().length() > 100
        || input.repositoryUrl() == null
        || input.repositoryUrl().length() > 2048)
      throw new BadRequestException("Name and repository URL are required");
    String name = input.name().trim();
    // The name is the project's URL path, for example /cisys/ in the UI.
    if (!NAME.matcher(name).matches()) throw badRequest(NAME_RULE);
    String url;
    try {
      url = gitUrl(input.repositoryUrl().trim());
    } catch (WebApplicationException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(RepositoryUrls.SHAPE);
    }
    try {
      repositoryUrls.validate(url);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    if (input.credentialRef() != null
        && !input.credentialRef().isBlank()
        && !identity.hasRole("admin")) throw new ForbiddenException();
    // Checked across all projects, including private projects the caller cannot see.
    if (Project.count("lower(name) = ?1", name.toLowerCase(Locale.ROOT)) > 0)
      throw conflict("A project with this name already exists");
    if (Project.count("repositoryUrl", url) > 0)
      throw conflict("A project with this repository URL already exists");
    Project p = new Project();
    p.name = name;
    p.repositoryUrl = url;
    p.privateRepository = input.privateRepository();
    p.ownerSubject = subject();
    p.credentialRef = credential(input.privateRepository(), input.credentialRef());
    try {
      p.persistAndFlush();
    } catch (PersistenceException e) {
      if (!isProjectUniqueViolation(e)) throw e;
      throw conflict("A project with this name or repository URL already exists");
    }
    return Response.status(201).entity(response(p)).build();
  }

  /** Sets or clears the read-only credential of a private project. Admins only. */
  @PUT
  @Path("/projects/{id}/credential")
  @Transactional
  public ProjectResponse credential(@PathParam("id") Long id, Credential input) {
    if (!identity.hasRole("admin")) throw new ForbiddenException();
    Project p = Project.findById(id);
    if (p == null) throw new NotFoundException();
    p.credentialRef = credential(p.privateRepository, input == null ? null : input.credentialRef());
    return response(p);
  }

  /** Names the backend reads as configuration, so they cannot be project credentials. */
  private static final Set<String> RESERVED_CREDENTIALS =
      Set.of(
          "CISYS_GIT_ALLOWED_HOSTS",
          "CISYS_GIT_ALLOWED_PORTS",
          "CISYS_GIT_ALLOWED_NETWORKS",
          "CISYS_GIT_INSECURE_TLS_HOSTS");

  /** The reference must name an environment variable; the token itself is never stored. */
  private static String credential(boolean privateRepository, String reference) {
    if (reference == null || reference.isBlank()) return null;
    if (!privateRepository) throw badRequest("Only private projects can use a credential");
    String trimmed = reference.trim();
    if (RESERVED_CREDENTIALS.contains(trimmed))
      throw badRequest("That environment variable configures the server, not a credential");
    if (!trimmed.matches("CISYS_GIT_[A-Z0-9_]{1,80}"))
      throw badRequest("Invalid credential reference");
    return trimmed;
  }

  /**
   * Deletes a project and its builds; the database unlinks their logs. Admins, or the lead who owns
   * it.
   */
  @DELETE
  @Path("/projects/{id}")
  @Transactional
  public Response delete(@PathParam("id") Long id) {
    if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
    BuildRunner.lockQueue();
    Project p = Project.findById(id);
    boolean admin = identity.hasRole("admin");
    boolean owner = p != null && subject().equals(p.ownerSubject);
    // Private projects the caller cannot see answer 404, as everywhere else.
    if (p == null || (p.privateRepository && !admin && !owner)) throw new NotFoundException();
    if (!admin && !owner) throw new ForbiddenException();
    if (Build.count("projectId = ?1 and status in ('RUNNING', 'QUEUED')", id) > 0)
      throw conflict("A build is queued or running. Delete the project when its builds finish.");
    Build.delete("projectId", id);
    p.delete();
    return Response.noContent().build();
  }

  /**
   * Updates a project. Admins can update any project; leads can only update their own projects.
   * Safe while builds are queued or running: a build snapshots its repository URL, branch, image,
   * command and credential reference when submitted, so in-flight builds keep building what was
   * requested and edits affect only new page views and new builds.
   */
  @PUT
  @Path("/projects/{id}")
  @Transactional
  public Response update(@PathParam("id") Long id, UpdateProject input) {
    if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
    Project p = Project.findById(id);
    boolean admin = identity.hasRole("admin");
    boolean owner = p != null && subject().equals(p.ownerSubject);
    // Private projects the caller cannot see answer 404, as everywhere else.
    if (p == null || (p.privateRepository && !admin && !owner)) throw new NotFoundException();
    if (!admin && !owner) throw new ForbiddenException();
    if (input == null
        || input.name() == null
        || input.name().isBlank()
        || input.name().length() > 100
        || input.repositoryUrl() == null
        || input.repositoryUrl().length() > 2048)
      throw new BadRequestException("Name and repository URL are required");
    String name = input.name().trim();
    if (!NAME.matcher(name).matches()) throw badRequest(NAME_RULE);
    String url;
    try {
      url = gitUrl(input.repositoryUrl().trim());
    } catch (WebApplicationException e) {
      throw e;
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(RepositoryUrls.SHAPE);
    }
    try {
      repositoryUrls.validate(url);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    // Exclude the current project from the uniqueness checks.
    if (Project.count("lower(name) = ?1 and id <> ?2", name.toLowerCase(Locale.ROOT), id) > 0)
      throw conflict("A project with this name already exists");
    if (Project.count("repositoryUrl = ?1 and id <> ?2", url, id) > 0)
      throw conflict("A project with this repository URL already exists");
    if (!admin && input.credentialRef() != null && !input.credentialRef().isBlank())
      throw new ForbiddenException();
    // A lead must not carry the admin-set credential to a new repository.
    boolean urlChanged = !Objects.equals(RepositoryUrls.normalize(p.repositoryUrl), url);
    p.name = name;
    p.repositoryUrl = url;
    p.privateRepository = input.privateRepository();
    if (admin) p.credentialRef = credential(input.privateRepository(), input.credentialRef());
    else if (urlChanged || !input.privateRepository()) p.credentialRef = null;
    try {
      p.persistAndFlush();
    } catch (PersistenceException e) {
      throw conflict("A project with this name or repository URL already exists");
    }
    return Response.ok(response(p)).build();
  }

  /**
   * Repository URLs name the Git repository itself: https://github.com/org/repo.git becomes
   * https://github.com/org/repo.
   */
  static String gitUrl(String url) {
    String trimmed = RepositoryUrls.normalize(url);
    URI parsed = URI.create(trimmed);
    String path = parsed.getPath();
    if (parsed.getQuery() != null
        || parsed.getFragment() != null
        || path == null
        || path.isEmpty()
        || path.endsWith("/.git"))
      throw badRequest("Use the repository's URL, for example https://github.com/org/repo.git");
    return trimmed;
  }

  private static boolean isProjectUniqueViolation(PersistenceException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "23505".equals(violation.getSQLState())
          && ("uk_project_name".equals(violation.getConstraintName())
              || "uk_project_name_lower".equals(violation.getConstraintName())
              || "uk_project_repositoryurl".equals(violation.getConstraintName()))) return true;
    }
    return false;
  }

  private static WebApplicationException badRequest(String message) {
    return new WebApplicationException(
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private static WebApplicationException conflict(String message) {
    return new WebApplicationException(
        Response.status(409)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
