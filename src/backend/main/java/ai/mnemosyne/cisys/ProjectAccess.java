/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Private projects are visible to members and admins only; everyone else gets a 404, so their
 * existence is not revealed. Visible but not allowed actions answer 403. Admins bypass membership.
 */
@ApplicationScoped
public class ProjectAccess {
    @Inject SecurityIdentity identity;

    public Project visible(String name) {
        Project p = Project.<Project>find("lower(name) = ?1", name.trim().toLowerCase(Locale.ROOT)).firstResult();
        if (p == null || (p.privateRepository && !canSeePrivate(p))) throw new NotFoundException();
        return p;
    }

    public Project visible(Long id) {
        requireCisysRole();
        Project p = Project.findById(id);
        if (p == null || (p.privateRepository && !canSeePrivate(p))) throw new NotFoundException();
        return p;
    }

    public List<Project> listVisible() {
        requireCisysRole();
        if (isAdmin()) return Project.listAll();
        return Project.list("privateRepository = false or id in (select projectId from ProjectMembership where subject = ?1)", subject());
    }

    public boolean isAdmin() { return identity.hasRole("admin"); }

    public boolean isMember(Project p) { return membership(p) != null; }

    public boolean isOwner(Project p) {
        ProjectMembership m = membership(p);
        return m != null && m.role == ProjectMembership.MembershipRole.OWNER;
    }

    public Project buildable(Long id) {
        Project p = visible(id);
        if (isAdmin()) return p;
        if (!identity.hasRole("lead")) throw new ForbiddenException();
        if (!isMember(p)) throw new ForbiddenException();
        return p;
    }

    public Project editable(Long id) {
        Project p = visible(id);
        if (canEdit(p)) return p;
        throw new ForbiddenException();
    }

    /** Editing, pinning included: admins, or a lead who owns the project. */
    public boolean canEdit(Project p) {
        return isAdmin() || (identity.hasRole("lead") && isOwner(p));
    }

    /** Pinning a build is an edit of its project; the project page uses this for its Pin button. */
    public boolean canPin(Project p) { return canEdit(p); }

    // Keeps the existing semantics: global lead or admin first, then owner or admin.
    public Project deletable(Long id) {
        if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
        Project p = visible(id);
        if (isAdmin() || isOwner(p)) return p;
        throw new ForbiddenException();
    }

    public Project manageable(Long id) {
        Project p = visible(id);
        if (isAdmin() || isOwner(p)) return p;
        throw new ForbiddenException();
    }

    public boolean canViewMembers(Project p) { return isAdmin() || isMember(p); }

    boolean canSeePrivate(Project p) {
        String sub = subjectOrNull();
        if (sub == null) return false;
        if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) return false;
        return isAdmin() || membership(p) != null;
    }

    private ProjectMembership membership(Project p) {
        String sub = subjectOrNull();
        if (sub == null) return null;
        return ProjectMembership.find("projectId = ?1 and subject = ?2", p.id, sub).firstResult();
    }

    private String subjectOrNull() {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken jwt)) return null;
        return jwt.getSubject();
    }

    String subject() {
        String sub = subjectOrNull();
        if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
        return sub;
    }

    void requireCisysRole() {
        if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
    }
}
