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
 @Inject BuildRunner runner;
 private Long project(boolean priv){return QuarkusTransaction.requiringNew().call(()->{Project p=new Project();String n=java.util.UUID.randomUUID().toString();p.name="example-"+n;p.repositoryUrl="https://github.com/octocat/Hello-World-"+n+".git";p.ownerSubject="owner-id";p.privateRepository=priv;p.persist();return p.id;});}
 private static void allow(String image){QuarkusTransaction.requiringNew().run(()->{if(AllowedImage.findById(image)==null){AllowedImage i=new AllowedImage();i.name=image;i.persist();}});}
 static final String REQUEST="{\"branch\":\"main\",\"image\":\"alpine:3.23\",\"command\":\"echo hello\"}";
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void privateLogsAreHidden(){Long id=project(true);given().get("/api/projects/"+id+"/builds").then().statusCode(404);}
 @Test @TestSecurity(user="member",roles="member") @JwtSecurity(claims=@Claim(key="sub",value="other-id")) void memberCanViewPublicButCannotTrigger(){Long id=project(false);given().get("/api/projects/"+id+"/builds").then().statusCode(200);given().contentType("application/json").body(REQUEST).post("/api/projects/"+id+"/builds").then().statusCode(403);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void ownerCanSeePrivateAndReachesTheRunner(){Long id=project(true);allow("alpine:3.23");given().get("/api/projects/"+id+"/builds").then().statusCode(200);runner.busy().set(true);try{given().contentType("application/json").body(REQUEST).post("/api/projects/"+id+"/builds").then().statusCode(409);}finally{runner.busy().set(false);}}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void rejectInvalidBranch(){Long id=project(true);given().contentType("application/json").body(REQUEST.replace("main","branch.lock")).post("/api/projects/"+id+"/builds").then().statusCode(400);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void leadCannotUseGlobalCredentials(){Long id=project(true);allow("alpine:3.23");given().contentType("application/json").body(REQUEST.replace("\"command\":", "\"credentialRef\":\"CISYS_GIT_SECRET\",\"command\":" )).post("/api/projects/"+id+"/builds").then().statusCode(403);}
 @Test @TestSecurity(user="lead",roles="lead") @JwtSecurity(claims=@Claim(key="sub",value="owner-id")) void rejectImageNotAllowed(){Long id=project(true);given().contentType("application/json").body(REQUEST.replace("alpine:3.23","busybox:not-allowed")).post("/api/projects/"+id+"/builds").then().statusCode(400);}

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
   given().get(path).then().statusCode(200).body("[0].credentialRef",equalTo(credentialRef));
   given().get(path+"/"+id).then().statusCode(200)
       .body("branch",equalTo("main"),"image",equalTo("alpine:3.23"),
           "command",equalTo(command),"credentialRef",equalTo(credentialRef));
  } finally {
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
   while(runner.busy().get() && System.nanoTime()<deadline) Thread.sleep(50);
   assertFalse(runner.busy().get(),"Build did not finish");
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
   legacy.status="PASSED";
   legacy.persist();
   Build build=new Build();
   build.projectId=projectId;
   build.status="PASSED";
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
}
