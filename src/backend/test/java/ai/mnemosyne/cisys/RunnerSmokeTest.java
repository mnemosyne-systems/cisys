/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
@QuarkusTest
@EnabledIfEnvironmentVariable(named="CISYS_RUNNER_SMOKE", matches="true")
class RunnerSmokeTest {
 @Inject BuildRunner runner;
 @Inject BuildLogs logs;
 @Test void publicRepositoryBuildCompletes() throws Exception {
  Long project=QuarkusTransaction.requiringNew().call(()->{Project p=new Project();p.name="smoke";p.repositoryUrl="https://github.com/octocat/Hello-World.git";p.ownerSubject="test";p.persist();return p.id;});
  Build created=runner.submit(project,"test","https://github.com/octocat/Hello-World.git",new BuildResource.RunRequest("master","alpine:3.23","test -f README && echo cisys-green",null));
  runner.dispatch();
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(2);
  while(System.nanoTime()<deadline){Build b=QuarkusTransaction.requiringNew().call(()->Build.findById(created.id));if(!(b.status==Build.Status.RUNNING || b.status==Build.Status.QUEUED)){String log=logs.readAll(b.id);assertEquals(Build.Status.PASSED,b.status,log);assertTrue(log.contains("cisys-green"));assertNotNull(b.revision);assertEquals(0,b.exitCode);assertEquals("master",b.branch);assertEquals("alpine:3.23",b.image);return;}Thread.sleep(500);}
  fail("Build did not finish within two minutes");
 }
}
