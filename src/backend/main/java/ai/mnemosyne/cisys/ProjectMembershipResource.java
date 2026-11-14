/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/** Project membership: list, add and remove members. Subjects never leave the server; usernames do. */
@Path("/api/projects/{projectId}/members")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProjectMembershipResource {
    @Inject ProjectAccess access;
    @Inject KeycloakUsers keycloak;

    public record MemberResponse(String username, String role) {}
    public record AddMember(String username) {}

    @GET
    public List<MemberResponse> list(@PathParam("projectId") Long id) {
        Project p = access.visible(id);
        if (!access.canViewMembers(p)) throw new ForbiddenException();
        return ProjectMembership.<ProjectMembership>find("projectId = ?1 order by id", id).list()
                .stream().map(m -> new MemberResponse(keycloak.usernameOf(m.subject), m.role.name())).toList();
    }

    @POST @Transactional
    public Response add(@PathParam("projectId") Long id, AddMember input) {
        access.manageable(id);
        if (input == null || input.username() == null || input.username().isBlank()) throw new BadRequestException("username is required");
        String username = input.username().trim();
        String subject;
        try { subject = keycloak.subjectOf(username); }
        catch (IllegalStateException e) { throw badGateway(e); }
        if (subject == null) throw new NotFoundException("No user named '" + username + "' exists in Keycloak");
        if (ProjectMembership.count("projectId = ?1 and subject = ?2", id, subject) > 0)
            throw conflict("'" + username + "' is already a member of this project");
        ProjectMembership m = new ProjectMembership();
        m.projectId = id;
        m.subject = subject;
        m.role = ProjectMembership.MembershipRole.MEMBER;
        m.createdAt = java.time.Instant.now();
        m.persist();
        return Response.status(201).entity(new MemberResponse(username, "MEMBER")).build();
    }

    @DELETE @Path("/{username}") @Transactional
    public Response remove(@PathParam("projectId") Long id, @PathParam("username") String username) {
        access.manageable(id);
        String subject;
        try { subject = keycloak.subjectOf(username); }
        catch (IllegalStateException e) { throw badGateway(e); }
        if (subject == null) throw new NotFoundException();
        ProjectMembership m = ProjectMembership.find("projectId = ?1 and subject = ?2", id, subject).firstResult();
        if (m == null) throw new NotFoundException("'" + username + "' is not a member of this project");
        if (m.role == ProjectMembership.MembershipRole.OWNER) throw conflict("The project owner cannot be removed");
        m.delete();
        return Response.noContent().build();
    }

    /** The details name Keycloak's admin endpoints, so they are logged and the client gets a generic message. */
    private static jakarta.ws.rs.WebApplicationException badGateway(IllegalStateException e) {
        Log.warnf("Keycloak user lookup failed: %s", e.getMessage());
        return new jakarta.ws.rs.WebApplicationException(Response.status(502).type(MediaType.APPLICATION_JSON).entity(Map.of("error", "Keycloak is unavailable; try again later")).build());
    }

    private static jakarta.ws.rs.WebApplicationException conflict(String message) {
        return new jakarta.ws.rs.WebApplicationException(Response.status(409).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build());
    }
}
