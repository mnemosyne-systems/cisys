/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
@QuarkusTest class BuildResourceTest {
 @org.junit.jupiter.api.AfterEach void releaseSyntheticRunningBuilds(){
  QuarkusTransaction.requiringNew().run(()->Build.update("status = ?1 where status = ?2 and repositoryUrl is null",Build.Status.FAILED,Build.Status.RUNNING));
 }

 @Inject BuildRunner runner;
 @Inject BuildLogs logs;
 private Long project(boolean priv){return QuarkusTransaction.requiringNew().call(()->{Project p=new Project();String n=java.util.UUID.randomUUID().toString();p.name="example-"+n;p.repositoryUrl="https://github.com/octocat/Hello-World-"+n+".git";p.ownerSubject="owner-id";p.privateRepository=priv;p.persist();return p.id;});}
 private static void allow(String image){QuarkusTransaction.requiringNew().run(()->{if(AllowedImage.findById(image)==null){AllowedImage i=new AllowedImage();i.name=image;i.persist();}});}
 private Long build(Long project,String status,String... logParts){Long id=QuarkusTransaction.requiringNew().call(()->{Build b=new Build();b.projectId=project;b.requestedBy="owner-id";b.status=Build.Status.valueOf(status);b.branch="main";b.image="alpine:3.23";b.exitCode="PASSED".equals(status)?0:null;b.startedAt=java.time.Instant.now();b.persist();return b.id;});if(logParts.length>0)try(var w=logs.open(id)){for(String part:logParts){byte[] bytes=part.getBytes(java.nio.charset.StandardCharsets.UTF_8);w.append(bytes,0,bytes.length);}}return id;}
 static final String REQUEST="{\"branch\":\"main\",\"image\":\"alpine:3.23\",\"command\":\"echo hello\"}";
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void privateLogsAreHidden(){Long id=project(true);given().get("/api/projects/"+id+"/builds").then().statusCode(404);}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void memberCanViewPublicButCannotTrigger(){Long id=project(false);given().get("/api/projects/"+id+"/builds").then().statusCode(200);given().contentType("application/json").body(REQUEST).post("/api/projects/"+id+"/builds").then().statusCode(403);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id"))
 void ownerCanQueueWhileRunningAndGetsClearCapacityError(){
  Long id=project(true);allow("alpine:3.23");build(id,"RUNNING");
  BuildRunner target=io.quarkus.arc.ClientProxy.unwrap(runner);int limit=target.queueLimit;target.queueLimit=1;
  String path="/api/projects/"+id+"/builds";
  try{
   given().contentType("application/json").body(REQUEST).post(path).then().statusCode(200)
       .body("status",equalTo("QUEUED"),"startedAt",nullValue());
   given().contentType("application/json").body(REQUEST).post(path).then().statusCode(429).body(containsString("queue is full"));
  }finally{
   target.queueLimit=limit;
   QuarkusTransaction.requiringNew().run(()->Build.delete("projectId = ?1 and status = ?2",id,Build.Status.QUEUED));
  }
 }

 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void rejectInvalidBranch(){Long id=project(true);given().contentType("application/json").body(REQUEST.replace("main","branch.lock")).post("/api/projects/"+id+"/builds").then().statusCode(400);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void leadCannotUseGlobalCredentials(){Long id=project(true);allow("alpine:3.23");given().contentType("application/json").body(REQUEST.replace("\"command\":", "\"credentialRef\":\"CISYS_GIT_SECRET\",\"command\":" )).post("/api/projects/"+id+"/builds").then().statusCode(403);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void rejectImageNotAllowed(){Long id=project(true);given().contentType("application/json").body(REQUEST.replace("alpine:3.23","busybox:not-allowed")).post("/api/projects/"+id+"/builds").then().statusCode(400);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void rejectHostResolvingToABlockedAddress(){Long id=project(false);allow("alpine:3.23");TestHostResolver.set("github.com","127.0.0.1");try{given().contentType("application/json").body(REQUEST).post("/api/projects/"+id+"/builds").then().statusCode(400);}finally{TestHostResolver.reset();}}

 @Test
 @TestSecurity(user="admin",roles="admin")
 @JwtSecurity(claims=@Claim(key="sub",value="admin-id"))
 void savesBuildRequestDetailsAndReturnsCredentialReferenceToAdmin() throws InterruptedException {
  Long projectId=project(true);
  allow("alpine:3.23");
  String path="/api/projects/"+projectId+"/builds";
  String command="x".repeat(4000);
  // Use an unset reference so the checkout fails before contacting the remote.
  String credentialRef="CISYS_GIT_TEST_"+java.util.UUID.randomUUID().toString().replace("-", "")
      .toUpperCase(java.util.Locale.ROOT);
  String request=REQUEST.replace("echo hello",command)
      .replace("}",",\"credentialRef\":\""+credentialRef+"\"}");
  try {
   int id=given().contentType("application/json").body(request).post(path).then()
       .statusCode(200).body("branch",equalTo("main"),"image",equalTo("alpine:3.23"),
           "command",equalTo(command),"credentialRef",equalTo(credentialRef))
       .extract().path("id");
   runner.dispatch();
   given().get(path).then().statusCode(200).body("[0].credentialRef",equalTo(credentialRef));
   given().get(path+"/"+id).then().statusCode(200)
       .body("branch",equalTo("main"),"image",equalTo("alpine:3.23"),
           "command",equalTo(command),"credentialRef",equalTo(credentialRef));
  } finally {
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
   while(runner.hasPendingBuilds() && System.nanoTime()<deadline) Thread.sleep(50);
   assertFalse(runner.hasPendingBuilds(),"Build did not finish");
  }
 }

 @Test
 @TestSecurity(user="member",roles="member")
 @JwtSecurity(claims=@Claim(key="sub",value="other-id"))
 void buildHistoryHidesCredentialReferenceAndSupportsLegacyBuilds() {
  Long projectId=project(false);
  Long buildId=QuarkusTransaction.requiringNew().call(()->{
   Build legacy=new Build();
   legacy.projectId=projectId;
   legacy.status=Build.Status.PASSED;
   legacy.persist();
   Build build=new Build();
   build.projectId=projectId;
   build.status=Build.Status.PASSED;
   build.branch="main";
   build.image="alpine:3.23";
   build.command="echo hello";
   build.credentialRef="CISYS_GIT_SECRET";
   build.persist();
   return build.id;
  });
  String path="/api/projects/"+projectId+"/builds";
  given().get(path).then().statusCode(200)
      .body("[0].branch",equalTo("main"),"[0].image",equalTo("alpine:3.23"),
          "[0].command",equalTo("echo hello"),"[0]",not(hasKey("credentialRef")),
          "[1].status",equalTo("PASSED"),"[1].branch",nullValue(),
          "[1].image",nullValue(),"[1].command",nullValue());
  given().get(path+"/"+buildId).then().statusCode(200)
      .body("branch",equalTo("main"),"$",not(hasKey("credentialRef")));
 }

 @Test void processOutputIsBounded()throws Exception{var r=BuildRunner.command(List.of("sh","-c","head -c 500000 /dev/zero"),10,1024);assertEquals(0,r.code());assertTrue(r.output().endsWith("[output truncated]\n"));assertTrue(r.output().length()<1100);}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void listOmitsOutput(){Long id=project(false);build(id,"PASSED","secret-log-text");given().get("/api/projects/"+id+"/builds").then().statusCode(200).body(not(containsString("secret-log-text"))).body(not(containsString("\"output\""))).body("[0].branch",equalTo("main")).body("[0].image",equalTo("alpine:3.23")).body("[0].exitCode",equalTo(0)).body("[0].logTruncated",equalTo(false));}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void logStreamsAppendedParts(){Long id=project(false);Long b=build(id,"PASSED","one\n","two\n","é\n");given().get("/api/projects/"+id+"/builds/"+b+"/log").then().statusCode(200).contentType(startsWith("text/plain")).body(equalTo("one\ntwo\né\n"));}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void privateLogIsHidden(){Long id=project(true);Long b=build(id,"PASSED","hidden");given().get("/api/projects/"+id+"/builds/"+b+"/log").then().statusCode(404);}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void logOfBuildInOtherProjectIs404(){Long id=project(false),other=project(false);Long b=build(other,"PASSED","elsewhere");given().get("/api/projects/"+id+"/builds/"+b+"/log").then().statusCode(404);}
 @Test void recoverKeepsPartialLog(){Long id=project(false);Long b=build(id,"RUNNING","partial\n");runner.recover(null);Build after=QuarkusTransaction.requiringNew().call(()->Build.findById(b));assertEquals(Build.Status.FAILED,after.status);assertNotNull(after.finishedAt);assertEquals("partial\nServer restarted during build\n",logs.readAll(b));}
 private static boolean pinned(Long build){return QuarkusTransaction.requiringNew().call(()->Build.<Build>findById(build).pinned);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void ownerPinsAndUnpins(){Long id=project(false);Long b=build(id,"PASSED");String pin="/api/projects/"+id+"/builds/"+b+"/pin";given().contentType("application/json").put(pin).then().statusCode(200).body("pinned",equalTo(true));assertTrue(pinned(b));given().get("/api/projects/"+id+"/builds").then().body("[0].pinned",equalTo(true));given().contentType("application/json").put(pin).then().statusCode(200).body("pinned",equalTo(true));given().contentType("application/json").delete(pin).then().statusCode(200).body("pinned",equalTo(false));assertFalse(pinned(b));}
 @Test @TestSecurity(user="admin",roles="admin") @JwtSecurity(claims=@Claim(key="sub",value="admin-id")) void adminPinsPrivateProjectOfOthers(){Long id=project(true);Long b=build(id,"FAILED");given().contentType("application/json").put("/api/projects/"+id+"/builds/"+b+"/pin").then().statusCode(200).body("pinned",equalTo(true));assertTrue(pinned(b));}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void otherLeadCannotPin(){Long id=project(false);Long b=build(id,"PASSED");given().contentType("application/json").put("/api/projects/"+id+"/builds/"+b+"/pin").then().statusCode(403);assertFalse(pinned(b));}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void memberCannotPinOrSeePrivate(){Long pub=project(false),priv=project(true);Long b=build(pub,"PASSED"),hidden=build(priv,"PASSED");given().contentType("application/json").put("/api/projects/"+pub+"/builds/"+b+"/pin").then().statusCode(403);given().contentType("application/json").put("/api/projects/"+priv+"/builds/"+hidden+"/pin").then().statusCode(404);assertFalse(pinned(b));assertFalse(pinned(hidden));}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void pinOfBuildInOtherProjectIs404(){Long id=project(false),other=project(false);Long b=build(other,"PASSED");given().contentType("application/json").put("/api/projects/"+id+"/builds/"+b+"/pin").then().statusCode(404);assertFalse(pinned(b));}

 private void requesterVisible(boolean visible){Long id=project(false);Long b=build(id,"PASSED");String path="/api/projects/"+id+"/builds";
  if(visible){given().get(path).then().statusCode(200).body("[0].requestedBy",equalTo("owner-id"));given().get(path+"/"+b).then().statusCode(200).body("requestedBy",equalTo("owner-id"));}
  else{given().get(path).then().statusCode(200).body("[0]",not(hasKey("requestedBy")));given().get(path+"/"+b).then().statusCode(200).body("$",not(hasKey("requestedBy")));}}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void memberDoesNotSeeRequester(){requesterVisible(false);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void leadDoesNotSeeRequester(){requesterVisible(false);}
 @Test @TestSecurity(user="admin",roles="admin") @JwtSecurity(claims=@Claim(key="sub",value="admin-id")) void adminSeesRequester(){requesterVisible(true);}
}
