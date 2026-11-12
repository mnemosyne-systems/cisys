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
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RetentionTest {
 @org.junit.jupiter.api.AfterEach void releaseSyntheticRunningBuilds(){
  QuarkusTransaction.requiringNew().run(()->Build.update("status = ?1 where status = ?2 and repositoryUrl is null",Build.Status.FAILED,Build.Status.RUNNING));
 }

    @Inject BuildRetention retention;
    @Inject BuildLogs logs;

    private final Instant now = Instant.now();

    private static Long project() {
        return QuarkusTransaction.requiringNew().call(() -> {
            String n = UUID.randomUUID().toString();
            Project p = new Project();
            p.name = "retention-" + n;
            p.repositoryUrl = "https://github.com/octocat/retention-" + n + ".git";
            p.ownerSubject = "owner-id";
            p.persist();
            return p.id;
        });
    }

    private static Long build(Long project, String status, Instant finishedAt, boolean pinned) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Build b = new Build();
            b.projectId = project;
            b.status=Build.Status.valueOf(status);
            b.startedAt = (finishedAt == null ? Instant.now() : finishedAt).minusSeconds(60);
            b.finishedAt = finishedAt;
            b.pinned = pinned;
            b.persist();
            return b.id;
        });
    }

    private static boolean exists(Long build) {
        return QuarkusTransaction.requiringNew().call(() -> Build.findById(build) != null);
    }

    private static long count(Long project) {
        return QuarkusTransaction.requiringNew().call(() -> Build.count("projectId", project));
    }

    private Instant daysAgo(int days) {
        return now.minus(Duration.ofDays(days));
    }

    @Test
    void deletesFinishedBuildsOlderThanMaxAge() {
        Long p = project();
        Long old = build(p, "FAILED", daysAgo(31), false), recent = build(p, "PASSED", daysAgo(29), false);
        retention.purge(now, 30, 50);
        assertFalse(exists(old));
        assertTrue(exists(recent));
    }

    @Test
    void keepsTheNewestBuildsOfEachProject() {
        Long p = project(), other = project();
        List<Long> builds = new ArrayList<>();
        for (int i = 0; i < 53; i++) builds.add(build(p, "PASSED", now.minusSeconds(60L * (53 - i)), false));
        for (int i = 0; i < 3; i++) build(other, "PASSED", daysAgo(1), false);
        retention.purge(now, 30, 50);
        assertEquals(50, count(p));
        for (int i = 0; i < 3; i++) assertFalse(exists(builds.get(i)), "oldest builds go first");
        assertTrue(exists(builds.get(3)));
        assertEquals(3, count(other), "each project has its own limit");
    }

    @Test
    void keepsPinnedAndRunningBuilds() {
        Long p = project();
        Long pinned = build(p, "FAILED", daysAgo(90), true), running = build(p, "RUNNING", null, false);
        QuarkusTransaction.requiringNew().run(() -> Build.update("startedAt = ?1 where id = ?2", daysAgo(90), running));
        retention.purge(now, 30, 50);
        assertTrue(exists(pinned));
        assertTrue(exists(running));
    }

    @Test
    void pinnedBuildsDoNotCountTowardsTheLimit() {
        Long p = project();
        Long pinned = build(p, "PASSED", daysAgo(2), true);
        for (int i = 0; i < 50; i++) build(p, "PASSED", daysAgo(1), false);
        retention.purge(now, 30, 50);
        assertEquals(51, count(p));
        assertTrue(exists(pinned));
    }

    @Test
    void zeroTurnsALimitOff() {
        Long p = project();
        Long old = build(p, "PASSED", daysAgo(400), false);
        for (int i = 0; i < 3; i++) build(p, "PASSED", daysAgo(1), false);
        retention.purge(now, 0, 0);
        assertTrue(exists(old));
        retention.purge(now, 0, 2);
        assertEquals(2, count(p));
        assertFalse(exists(old));
    }

    @Test
    void deletesInBatches() {
        Long p = project();
        for (int i = 0; i < BuildRetention.BATCH + 5; i++) build(p, "PASSED", daysAgo(40), false);
        assertTrue(retention.purge(now, 30, 50) >= BuildRetention.BATCH + 5);
        assertEquals(0, count(p));
    }

    @Test
    void deletesTheLogWithItsBuild() {
        Long p = project();
        Long old = build(p, "PASSED", daysAgo(31), false);
        try (var w = logs.open(old)) { w.line("expired"); }
        long oid = logs.logOid(old);
        retention.purge(now, 30, 50);
        long left = QuarkusTransaction.requiringNew().call(() -> ((Number) Build.getEntityManager()
                .createNativeQuery("select count(*) from pg_largeobject_metadata where oid = cast(?1 as oid)")
                .setParameter(1, oid).getSingleResult()).longValue());
        assertEquals(0, left);
    }

    private static void saveSettings(int days, int maxBuilds) {
        QuarkusTransaction.requiringNew().run(() -> {
            Settings s = Settings.findById(1L);
            if (s == null) { s = new Settings(); s.persist(); }
            s.retentionDays = days;
            s.retentionMaxBuilds = maxBuilds;
        });
    }

    @Test
    void scheduledRunUsesTheAdminSettings() {
        Long p = project();
        Long build = build(p, "PASSED", daysAgo(20), false);
        retention.scheduled();
        assertTrue(exists(build), "kept under the default 30 days");
        try {
            saveSettings(10, 50);
            retention.scheduled();
            assertFalse(exists(build));
        } finally {
            saveSettings(30, 50);
        }
    }
}
