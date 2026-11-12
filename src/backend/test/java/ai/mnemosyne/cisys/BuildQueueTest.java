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
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BuildQueueTest {
 @Inject BuildRunner runner;
 @Inject BuildLogs logs;
 @Inject BuildRetention retention;
 Long project;
 final BuildResource.RunRequest request=new BuildResource.RunRequest("main","alpine:3.23","echo queued","CISYS_GIT_TEST");
 @BeforeEach void setup(){
  runner=io.quarkus.arc.ClientProxy.unwrap(runner);
  QuarkusTransaction.requiringNew().run(()->{Build.deleteAll();Project.deleteAll();});
  runner.concurrency=1;runner.queueLimit=100;
  project=QuarkusTransaction.requiringNew().call(()->{
   Project p=new Project();p.name="queue-test";p.ownerSubject="owner";p.repositoryUrl="https://github.com/example/test.git";p.persist();return p.id;
  });
 }
 @AfterEach void cleanup(){runner.concurrency=1;runner.queueLimit=100;QuarkusTransaction.requiringNew().run(()->{Build.deleteAll();Project.deleteAll();});}
 Build submit(){return runner.submit(project,"owner","https://github.com/example/original.git",request);}
 Build read(Long id){return QuarkusTransaction.requiringNew().call(()->Build.findById(id));}
 void finish(Long id){QuarkusTransaction.requiringNew().run(()->{Build b=Build.findById(id);b.status=Build.Status.PASSED;b.finishedAt=java.time.Instant.now();});}
 @Test void fifoAndSlots(){
  Build first=submit(),second=submit(),third=submit();
  assertEquals(Build.Status.QUEUED,first.status);assertNull(first.startedAt);
  assertEquals(first.id,runner.claim().id);assertNull(runner.claim());
  finish(first.id);assertEquals(second.id,runner.claim().id);
  finish(second.id);assertEquals(third.id,runner.claim().id);assertNull(runner.claim());
  Build stored=read(third.id);assertNotNull(stored.startedAt);
  assertEquals("https://github.com/example/original.git",stored.repositoryUrl);
  assertEquals(request.branch(),stored.branch);assertEquals(request.command(),stored.command);
  assertEquals(request.image(),stored.image);assertEquals(request.credentialRef(),stored.credentialRef);
 }
 @Test void concurrentClaimsNeverDuplicateOrExceedCapacity()throws Exception{
  runner.concurrency=3;for(int i=0;i<10;i++)submit();
  Set<Long> claimed=new HashSet<>();
  try(var pool=Executors.newFixedThreadPool(8)){
   List<Callable<Build>> calls=new ArrayList<>();for(int i=0;i<16;i++)calls.add(runner::claim);
   for(Future<Build> f:pool.invokeAll(calls)){Build b=f.get();if(b!=null)assertTrue(claimed.add(b.id));}
  }
  assertEquals(3,claimed.size());
  assertEquals(3L,QuarkusTransaction.requiringNew().call(()->Build.count("status",Build.Status.RUNNING)));
 }
 @Test void concurrentAdmissionsRespectQueueLimit()throws Exception{
  runner.queueLimit=3;int accepted=0,rejected=0;
  try(var pool=Executors.newFixedThreadPool(8)){
   List<Callable<Boolean>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->{try{submit();return true;}catch(WebApplicationException e){assertEquals(429,e.getResponse().getStatus());return false;}});
   for(Future<Boolean> f:pool.invokeAll(calls)){if(f.get())accepted++;else rejected++;}
  }
  assertEquals(3,accepted);assertEquals(9,rejected);
 }
 @Test void recoveryKeepsQueuedRequestsAndFailsInterruptedBuilds(){
  Build interrupted=submit(),waiting=submit();runner.claim();
  BuildRunner restarted=new BuildRunner();restarted.logs=logs;restarted.concurrency=1;restarted.queueLimit=100;
  try{
   restarted.recover(null);
   assertEquals(Build.Status.FAILED,read(interrupted.id).status);assertNotNull(read(interrupted.id).finishedAt);
   assertEquals(Build.Status.QUEUED,read(waiting.id).status);assertNull(read(waiting.id).startedAt);
   assertEquals(waiting.id,restarted.claim().id);assertNull(restarted.claim());
  }finally{restarted.stop();}
 }
 @Test void dispatcherRunsWaitingBuildAfterSlotBecomesFree()throws Exception{
  Build first=submit(),second=submit();
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
  List<Long> order=new CopyOnWriteArrayList<>();
  BuildRunner worker=new BuildRunner(){
   @Override void execute(Build b){
    order.add(b.id);
    try{if(b.id.equals(first.id)){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}finish(b.id);}
    catch(InterruptedException e){Thread.currentThread().interrupt();}
    finally{done.countDown();}
   }
  };
  worker.logs=logs;worker.concurrency=1;worker.queueLimit=100;worker.recover(null);
  try{
   worker.dispatch();assertTrue(entered.await(10,TimeUnit.SECONDS));
   worker.dispatch();assertEquals(Build.Status.QUEUED,read(second.id).status);
   release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS));
   worker.dispatch();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(read(second.id).status!=Build.Status.PASSED && System.nanoTime()<deadline)Thread.sleep(10);
   assertEquals(Build.Status.PASSED,read(second.id).status);
   assertEquals(List.of(first.id,second.id),order);
  }finally{release.countDown();worker.stop();}
 }
 @Test void failedCompletionIsRetriedWithoutRerunningAndQueueResumes()throws Exception{
  Build first=submit(),second=submit();
  CountDownLatch attempted=new CountDownLatch(1),secondDone=new CountDownLatch(1);
  var failures=new java.util.concurrent.atomic.AtomicInteger(2);
  List<Long> executions=new CopyOnWriteArrayList<>();
  var finished=java.time.Instant.parse("2026-11-12T10:00:00Z");
  BuildRunner worker=new BuildRunner(){
   @Override void execute(Build b){
    executions.add(b.id);
    complete(new Completion(b.id,Build.Status.PASSED,"revision",0,"image-digest",true,finished));
    if(b.id.equals(first.id))attempted.countDown();else secondDone.countDown();
   }
   @Override void persistCompletion(Completion r){
    super.persistCompletion(r);
    // Fail after the SQL update so the test also verifies transaction rollback.
    if(r.id().equals(first.id) && failures.getAndDecrement()>0)throw new IllegalStateException("database unavailable");
   }
  };
  worker.logs=logs;worker.concurrency=1;worker.queueLimit=100;worker.recover(null);
  try{
   worker.dispatch();assertTrue(attempted.await(10,TimeUnit.SECONDS));
   assertEquals(Build.Status.RUNNING,read(first.id).status);
   assertNull(read(first.id).finishedAt);
   worker.dispatch(); // The second persistence attempt still fails.
   assertEquals(Build.Status.RUNNING,read(first.id).status);
   assertEquals(Build.Status.QUEUED,read(second.id).status);
   worker.dispatch(); // Database recovery frees the slot and starts the next build.
   assertTrue(secondDone.await(10,TimeUnit.SECONDS));
   Build saved=read(first.id);
   assertEquals(Build.Status.PASSED,saved.status);assertEquals(finished,saved.finishedAt);
   assertEquals("revision",saved.revision);assertEquals(0,saved.exitCode);
   assertEquals("image-digest",saved.imageDigest);assertTrue(saved.logTruncated);
   assertEquals(Build.Status.PASSED,read(second.id).status);
   worker.dispatch();assertEquals(List.of(first.id,second.id),executions);
  }finally{worker.stop();}
 }
 @Test void retentionProtectsQueuedBuilds(){
  Build waiting=submit();
  QuarkusTransaction.requiringNew().run(()->{Build b=Build.findById(waiting.id);b.startedAt=java.time.Instant.EPOCH;});
  retention.purge(java.time.Instant.now(),1,1);
  assertEquals(Build.Status.QUEUED,read(waiting.id).status);
 }
}
