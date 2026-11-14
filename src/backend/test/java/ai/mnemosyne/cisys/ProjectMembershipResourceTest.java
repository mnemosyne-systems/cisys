/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest @TestProfile(MembershipTestProfile.class)
class ProjectMembershipResourceTest {

    @BeforeEach
    void users() {
        TestKeycloakUsers.reset();
        TestKeycloakUsers.user("owner", "owner-id");
        TestKeycloakUsers.user("member", "member-id");
        TestKeycloakUsers.user("admin", "admin-id");
        TestKeycloakUsers.user("other", "other-id");
    }

    private Long project(boolean priv) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Project p = new Project();
            String n = UUID.randomUUID().toString();
            p.name = "members-" + n;
            p.repositoryUrl = "https://github.com/octocat/members-" + n + ".git";
            p.ownerSubject = "owner-id";
            p.privateRepository = priv;
            p.persist();
            membership(p.id, "owner-id", ProjectMembership.MembershipRole.OWNER);
            return p.id;
        });
    }

    private static void membership(Long projectId, String subject, ProjectMembership.MembershipRole role) {
        ProjectMembership m = new ProjectMembership();
        m.projectId = projectId;
        m.subject = subject;
        m.role = role;
        m.createdAt = java.time.Instant.now();
        m.persist();
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerSeesMembers() {
        Long id = project(true);
        given().get("/api/projects/" + id + "/members").then().statusCode(200).body("$", hasSize(1))
                .body("[0].username", equalTo("owner"), "[0].role", equalTo("OWNER"));
    }

    @Test @TestSecurity(user = "member", roles = "member") @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
    void projectMemberSeesMembers() {
        Long id = project(true);
        QuarkusTransaction.requiringNew().run(() -> membership(id, "member-id", ProjectMembership.MembershipRole.MEMBER));
        given().get("/api/projects/" + id + "/members").then().statusCode(200).body("$", hasSize(2));
    }

    @Test @TestSecurity(user = "admin", roles = "admin") @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
    void adminSeesMembers() {
        Long id = project(true);
        given().get("/api/projects/" + id + "/members").then().statusCode(200).body("$", hasSize(1));
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
    void outsiderGets404OnPrivateProject() {
        Long id = project(true);
        given().get("/api/projects/" + id + "/members").then().statusCode(404);
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
    void outsiderGets403OnPublicProject() {
        Long id = project(false);
        given().get("/api/projects/" + id + "/members").then().statusCode(403);
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerAddsMember() {
        Long id = project(true);
        given().contentType(ContentType.JSON).body("{\"username\":\"member\"}").post("/api/projects/" + id + "/members")
                .then().statusCode(201).body("username", equalTo("member"), "role", equalTo("MEMBER"));
        given().get("/api/projects/" + id + "/members").then().statusCode(200).body("$", hasSize(2));
    }

    @Test @TestSecurity(user = "admin", roles = "admin") @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
    void adminAddsMember() {
        Long id = project(false);
        given().contentType(ContentType.JSON).body("{\"username\":\"member\"}").post("/api/projects/" + id + "/members")
                .then().statusCode(201);
    }

    @Test @TestSecurity(user = "member", roles = "member") @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
    void memberCannotAdd() {
        Long id = project(false);
        QuarkusTransaction.requiringNew().run(() -> membership(id, "member-id", ProjectMembership.MembershipRole.MEMBER));
        given().contentType(ContentType.JSON).body("{\"username\":\"other\"}").post("/api/projects/" + id + "/members").then().statusCode(403);
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void unknownUsernameIs404() {
        Long id = project(false);
        given().contentType(ContentType.JSON).body("{\"username\":\"ghost\"}").post("/api/projects/" + id + "/members").then().statusCode(404);
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void existingMemberIs409() {
        Long id = project(false);
        given().contentType(ContentType.JSON).body("{\"username\":\"member\"}").post("/api/projects/" + id + "/members").then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"username\":\"member\"}").post("/api/projects/" + id + "/members").then().statusCode(409);
    }

    @Test @TestSecurity(user = "admin", roles = "admin") @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
    void adminRemovesMember() {
        Long id = project(false);
        QuarkusTransaction.requiringNew().run(() -> membership(id, "member-id", ProjectMembership.MembershipRole.MEMBER));
        given().delete("/api/projects/" + id + "/members/member").then().statusCode(204);
    }

    @Test @TestSecurity(user = "admin", roles = "admin") @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
    void adminCannotRemoveProjectOwner() {
        Long id = project(false);
        given().delete("/api/projects/" + id + "/members/owner").then().statusCode(409);
    }

    @Test @TestSecurity(user = "member", roles = "member") @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
    void memberCannotRemove() {
        Long id = project(false);
        QuarkusTransaction.requiringNew().run(() -> membership(id, "member-id", ProjectMembership.MembershipRole.MEMBER));
        given().delete("/api/projects/" + id + "/members/owner").then().statusCode(403);
    }

    @Test @TestSecurity(user = "member", roles = "member") @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
    void revokedMembershipLosesAccess() {
        Long id = project(true);
        QuarkusTransaction.requiringNew().run(() -> membership(id, "member-id", ProjectMembership.MembershipRole.MEMBER));
        given().get("/api/projects/" + id + "/builds").then().statusCode(200);
        QuarkusTransaction.requiringNew().run(() -> ProjectMembership.delete("projectId = ?1 and subject = ?2", id, "member-id"));
        given().get("/api/projects/" + id + "/builds").then().statusCode(404);
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerCannotBeRemoved() {
        Long id = project(false);
        given().delete("/api/projects/" + id + "/members/owner").then().statusCode(409);
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerRemovesMember() {
        Long id = project(false);
        QuarkusTransaction.requiringNew().run(() -> membership(id, "member-id", ProjectMembership.MembershipRole.MEMBER));
        given().delete("/api/projects/" + id + "/members/member").then().statusCode(204);
        assertEquals(1, QuarkusTransaction.requiringNew().call(() ->
                ProjectMembership.count("projectId = ?1", id)));
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void removingNonMemberIs404() {
        Long id = project(false);
        given().delete("/api/projects/" + id + "/members/member").then().statusCode(404);
    }

    @Test @TestSecurity(user = "lead", roles = "lead") @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void keycloakFailureIsGeneric502() {
        Long id = project(false);
        TestKeycloakUsers.unavailable();
        given().contentType(ContentType.JSON).body("{\"username\":\"member\"}").post("/api/projects/" + id + "/members")
                .then().statusCode(502).body("error", equalTo("Keycloak is unavailable; try again later"));
        given().delete("/api/projects/" + id + "/members/member")
                .then().statusCode(502).body("error", equalTo("Keycloak is unavailable; try again later"));
    }
}
