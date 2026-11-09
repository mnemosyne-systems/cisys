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
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@Path("/api/projects/{projectId}/builds") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @Authenticated
public class BuildResource {
 @Inject SecurityIdentity identity;
 @Inject BuildRunner runner;
 public record RunRequest(String branch, String image, String command, String credentialRef) {}
 public record BuildResponse(Long id, Long projectId, String requestedBy, String status,
     String revision, Instant startedAt, Instant finishedAt, String output, String branch,
     String image, String command, @JsonInclude(JsonInclude.Include.NON_NULL) String credentialRef) {}

 private BuildResponse response(Build build) {
  return new BuildResponse(build.id, build.projectId, build.requestedBy, build.status,
      build.revision, build.startedAt, build.finishedAt, build.output, build.branch,
      build.image, build.command, identity.hasRole("admin") ? build.credentialRef : null);
 }
 private Project visible(Long id) {
  if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
  if (!(identity.getPrincipal() instanceof JsonWebToken jwt) || jwt.getSubject()==null) throw new NotAuthorizedException("Bearer");
  Project p=Project.findById(id);
  if(p==null || (p.privateRepository && !identity.hasRole("admin") && !jwt.getSubject().equals(p.ownerSubject))) throw new NotFoundException();
  return p;
 }
 @GET public List<BuildResponse> list(@PathParam("projectId") Long id) {
  visible(id);
  return Build.<Build>find("projectId = ?1 order by id desc",id).page(0,50).list()
      .stream().map(this::response).toList();
 }
 @GET @Path("/{buildId}") public BuildResponse detail(@PathParam("projectId") Long id,@PathParam("buildId") Long buildId) {
  visible(id); Build b=Build.findById(buildId);if(b==null || !id.equals(b.projectId))throw new NotFoundException();return response(b);
 }
 @POST public BuildResponse start(@PathParam("projectId") Long id,RunRequest input) {
  Project p=visible(id);
  if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
  if(input==null || input.branch()==null || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/"+input.branch()) || input.branch().startsWith("-") || input.branch().length()>150)throw new BadRequestException("Invalid branch");
  if(input.image()==null || !input.image().matches(AllowedImage.PATTERN) || input.command()==null || input.command().isBlank() || input.command().length()>4000)throw new BadRequestException("Image and command are required");
  if(!AllowedImage.isAllowed(input.image()))throw new BadRequestException("Image is not allowed");
  if(input.credentialRef()!=null && !identity.hasRole("admin"))throw new ForbiddenException();
  if(input.credentialRef()!=null && !input.credentialRef().matches("CISYS_GIT_[A-Z0-9_]{1,80}"))throw new BadRequestException("Invalid credential reference");
  URI uri=URI.create(p.repositoryUrl);
  if(!"https".equals(uri.getScheme()) || !List.of("github.com","gitlab.com").contains(uri.getHost()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))throw new BadRequestException("Builds require an HTTPS github.com or gitlab.com URL without credentials");
  return response(runner.submit(id,((JsonWebToken)identity.getPrincipal()).getSubject(),p.repositoryUrl,input));
 }
}
