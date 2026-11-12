/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests share one database and run in any order, so counts are compared before and after each change. */
@QuarkusTest
class StatsResourceTest {
 @org.junit.jupiter.api.AfterEach void releaseSyntheticRunningBuilds(){
  QuarkusTransaction.requiringNew().run(()->Build.update("status = ?1 where status = ?2 and repositoryUrl is null",Build.Status.FAILED,Build.Status.RUNNING));
 }

    private static final List<String> FIELDS = List.of("projects", "builds", "passed", "failed", "running", "buildsLast7Days");

    private static String raw() {
        return given().get("/api/stats/public").then().statusCode(200).extract().asString();
    }

    // Read decimals as double: the default float would round successRate (0.666... becomes 0.6666667).
    private static final JsonPathConfig DOUBLES = new JsonPathConfig(JsonPathConfig.NumberReturnType.DOUBLE);

    private static JsonPath stats() { return JsonPath.from(raw()).using(DOUBLES); }

    private static Long project(boolean privateRepository) {
        String id = UUID.randomUUID().toString();
        return QuarkusTransaction.requiringNew().call(() -> {
            Project p = new Project();
            p.name = "stats-" + id; p.repositoryUrl = "https://github.com/test/" + id + ".git";
            p.privateRepository = privateRepository; p.ownerSubject = "stats-owner";
            p.persist();
            return p.id;
        });
    }

    private static void build(Long projectId, String status, Instant startedAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            Build b = new Build();
            b.projectId = projectId; b.requestedBy = "stats-owner"; b.status=Build.Status.valueOf(status); b.startedAt = startedAt;
            if (!"RUNNING".equals(status)) b.finishedAt = startedAt.plusSeconds(10);
            b.persist();
        });
    }

    private static long delta(JsonPath before, JsonPath after, String field) {
        return after.getLong(field) - before.getLong(field);
    }

    @Test void anonymousCanReadStatsWithCacheHeader() {
        given().get("/api/stats/public").then().statusCode(200).header("Cache-Control", startsWith("public, max-age="));
    }

    @Test void doesNotExposePrivateProjectCounts() {
        String body = raw();
        assertFalse(body.contains("privateProjects"));
        assertFalse(body.contains("publicProjects"));
    }

    @Test void countsOnlyPublicBuildsWithTheirStatusesAndDates() {
        JsonPath before = stats();
        Long publicProject = project(false), privateProject = project(true);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant latestPublic = now.plus(1, ChronoUnit.HOURS);

        build(publicProject, "PASSED", now.minus(1, ChronoUnit.DAYS));
        build(publicProject, "PASSED", now.minus(10, ChronoUnit.DAYS)); // outside the last 7 days
        build(publicProject, "FAILED", now.minus(2, ChronoUnit.DAYS));
        build(publicProject, "RUNNING", latestPublic);
        // Private builds, including a newer one, must not show up anywhere.
        build(privateProject, "PASSED", now);
        build(privateProject, "FAILED", now.plus(2, ChronoUnit.HOURS));

        JsonPath after = stats();
        assertEquals(1, delta(before, after, "projects"));
        assertEquals(4, delta(before, after, "builds"));
        assertEquals(2, delta(before, after, "passed"));
        assertEquals(1, delta(before, after, "failed"));
        assertEquals(1, delta(before, after, "running"));
        assertEquals(3, delta(before, after, "buildsLast7Days"));
        assertEquals(latestPublic, Instant.parse(after.getString("lastBuildAt")));
        double expectedRate = (double) after.getLong("passed") / (after.getLong("passed") + after.getLong("failed"));
        assertEquals(expectedRate, after.getDouble("successRate"), 1e-9);
    }

    @Test void privateProjectAndItsBuildsChangeNothing() {
        JsonPath before = stats();
        Long privateProject = project(true);
        build(privateProject, "PASSED", Instant.now());
        build(privateProject, "FAILED", Instant.now());
        JsonPath after = stats();
        for (String field : FIELDS) assertEquals(0, delta(before, after, field), field);
        assertEquals(before.getString("lastBuildAt"), after.getString("lastBuildAt"));
        assertEquals(before.getString("successRate"), after.getString("successRate"));
    }

    @Test void successRateIsNullWithoutFinishedPublicBuilds() {
        JsonPath current = stats();
        if (current.getLong("passed") + current.getLong("failed") == 0) assertNull(current.get("successRate"));
    }

    @Test @TestSecurity(user = "admin", roles = "admin")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "stats-admin-id")})
    void countsIncreaseWithoutRevealingProjects() {
        JsonPath before = stats();
        String id = UUID.randomUUID().toString();
        String publicName = "stats-pub-" + id, privateName = "stats-priv-" + id;
        String publicUrl = "https://github.com/test/pub-" + id + ".git", privateUrl = "https://github.com/test/priv-" + id + ".git";
        given().contentType("application/json").body(Map.of("name", publicName, "repositoryUrl", publicUrl, "privateRepository", false)).post("/api/projects").then().statusCode(201);
        Long privateId = given().contentType("application/json").body(Map.of("name", privateName, "repositoryUrl", privateUrl, "privateRepository", true))
                .post("/api/projects").then().statusCode(201).extract().jsonPath().getLong("id");
        build(privateId, "PASSED", Instant.now());

        String raw = raw();
        JsonPath after = JsonPath.from(raw).using(DOUBLES);
        assertEquals(1, delta(before, after, "projects"));
        for (String field : FIELDS.subList(1, FIELDS.size())) assertEquals(0, delta(before, after, field), field);
        for (String secret : new String[] {id, publicName, privateName, publicUrl, privateUrl}) assertFalse(raw.contains(secret), secret);
        for (String key : new String[] {"\"name\"", "\"repositoryUrl\"", "\"ownerSubject\""}) assertFalse(raw.contains(key), key);
    }
}
