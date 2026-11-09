/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import io.quarkus.logging.Log;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped public class BuildRunner {
 String runtime;
 /** Uses podman when it is installed, otherwise docker. */
 @PostConstruct void resolveRuntime(){
  runtime=onPath("podman")?"podman":onPath("docker")?"docker":"podman";
  Log.infof("Build runner uses the %s container runtime",runtime);
 }
 String runtime(){return runtime;}
 private static boolean onPath(String program){
  for(String dir:System.getenv().getOrDefault("PATH","").split(File.pathSeparator)){if(!dir.isEmpty()&&Files.isExecutable(Path.of(dir,program)))return true;}
  return false;
 }
 private final AtomicBoolean busy=new AtomicBoolean();
 /** For tests: whether a build is running. */
 AtomicBoolean busy(){return busy;}
 private final ExecutorService executor=Executors.newSingleThreadExecutor();
 void recover(@Observes StartupEvent event){QuarkusTransaction.requiringNew().run(()->Build.update("status = 'FAILED', finishedAt = ?1, output = 'Server restarted during build' where status = 'RUNNING'",Instant.now()));}
 Build submit(Long projectId,String actor,String url,BuildResource.RunRequest request){
  if(!busy.compareAndSet(false,true))throw new WebApplicationException("Runner busy",409);
  try {
   Build build=QuarkusTransaction.requiringNew().call(()->{
    Build b=new Build();
    b.projectId=projectId;
    b.requestedBy=actor;
    b.status="RUNNING";
    b.startedAt=Instant.now();
    b.output="Preparing checkout";
    b.branch=request.branch();
    b.image=request.image();
    b.command=request.command();
    b.credentialRef=request.credentialRef();
    b.persist();
    return b;
   });
   executor.submit(()->run(build.id,url,request));
   return build;
  } catch(RuntimeException e){busy.set(false);throw e;}
 }
 private void run(Long id,String url,BuildResource.RunRequest request){
  Path dir=null;String name="cisys-build-"+id, output="",revision=null,status="FAILED";
  try {
   dir=Files.createTempDirectory("cisys-");
   try(Git git=Git.init().setDirectory(dir.toFile()).call()){
    var cfg=git.getRepository().getConfig();cfg.setString("http",null,"followRedirects","false");cfg.save();
    var fetch=git.fetch().setRemote(url).setRefSpecs(new RefSpec("+refs/heads/"+request.branch()+":refs/remotes/origin/build")).setDepth(1).setTimeout(60);
    if(request.credentialRef()!=null){String token=System.getenv(request.credentialRef());if(token==null || token.isBlank())throw new IllegalStateException("Missing Git credential");fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider("oauth2",token));}
    fetch.call();git.checkout().setName("build").setCreateBranch(true).setStartPoint("refs/remotes/origin/build").call();revision=git.getRepository().resolve("HEAD").name();
   }
   delete(dir.resolve(".git"));
   try(var paths=Files.walk(dir)){for(Path path:paths.toList()){if(path.equals(dir)||Files.isSymbolicLink(path))continue;Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(Files.isDirectory(path)||Files.isExecutable(path)?"rwxr-xr-x":"rw-r--r--"));}}
   checked(List.of(runtime,"create","--name",name,"--network=none","--log-driver=none","--cap-drop=ALL","--security-opt=no-new-privileges","--pids-limit=256","--memory=1g","--cpus=1","--user=65534:65534","--workdir=/workspace",request.image(),"sh","-c","cp -R /workspace/. /tmp/build && cd /tmp/build && exec sh -c \"$1\"","cisys",request.command()),120);
   checked(List.of(runtime,"cp",dir.toString()+"/.",name+":/workspace"),60);
   Result result=command(List.of(runtime,"start","--attach",name),600,120000);output=result.output();
   if(result.timeout()){try{checked(List.of(runtime,"kill",name),15);}catch(Exception ignored){}output="Build timed out\n"+output;}
   else {String exit=checked(List.of(runtime,"inspect","--format={{.State.ExitCode}}",name),15).trim();status="0".equals(exit)?"PASSED":"FAILED";}
  } catch(Exception e){output="Build failed during checkout or container execution ("+e.getClass().getSimpleName()+"). Check the runner configuration.";}
  finally {
   try{checked(List.of(runtime,"rm","-f",name),15);}catch(Exception ignored){}
   if(dir!=null)try{delete(dir);}catch(Exception ignored){}
   String result=status,text=output.replace("\u0000","\uFFFD"),sha=revision;
   try{QuarkusTransaction.requiringNew().run(()->{Build b=Build.findById(id);b.status=result;b.output=text;b.revision=sha;b.finishedAt=Instant.now();});}finally{busy.set(false);}
  }
 }
 record LocalImage(String name,String id,String size,String created){}
 /** Lists the tagged images in the local container runtime. */
 List<LocalImage> images()throws Exception{
  Result r=command(List.of(runtime,"images","--format","{{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}\t{{.CreatedSince}}"),30,1000000);
  if(r.timeout()||r.code()!=0)throw new IllegalStateException("Container command failed");
  Map<String,LocalImage> images=new TreeMap<>();
  for(String line:r.output().split("\n")){
   String[] f=line.split("\t");
   if(f.length<5||f[0].equals("<none>")||f[1].equals("<none>"))continue;
   String name=f[0]+":"+f[1];
   if(name.matches(AllowedImage.PATTERN))images.putIfAbsent(name,new LocalImage(name,f[2],f[3],f[4]));
  }
  return List.copyOf(images.values());
 }
 /** Pulls an image into the local container runtime. Returns the runtime's output when the pull fails. */
 Optional<String> pull(String name)throws Exception{Result r=command(List.of(runtime,"pull",name),600,8192);return r.code()==0&&!r.timeout()?Optional.empty():Optional.of(r.timeout()?"Pull timed out":tail(r.output()));}
 /** Removes an image from the local container runtime. Returns the runtime's output when the removal fails. */
 Optional<String> remove(String name)throws Exception{Result r=command(List.of(runtime,"rmi",name),60,8192);return r.code()==0&&!r.timeout()?Optional.empty():Optional.of(r.timeout()?"Removal timed out":tail(r.output()));}
 private static String tail(String s){s=s.strip();return s.length()>500?"…"+s.substring(s.length()-500):s;}
 record Result(int code,boolean timeout,String output){}
 static Result command(List<String> args,int timeout,int limit)throws Exception{
  Process p=new ProcessBuilder(args).redirectErrorStream(true).start();p.getOutputStream().close();ByteArrayOutputStream buffer=new ByteArrayOutputStream();boolean[] truncated={false};
  Thread reader=Thread.ofPlatform().daemon(true).start(()->{try(var in=p.getInputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){synchronized(buffer){int count=Math.min(n,limit-buffer.size());buffer.write(bytes,0,count);if(count<n)truncated[0]=true;}}}catch(IOException ignored){}});
  try{boolean done=p.waitFor(timeout,TimeUnit.SECONDS);if(!done){p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}reader.join(2000);synchronized(buffer){return new Result(done?p.exitValue():-1,!done,buffer.toString(StandardCharsets.UTF_8)+(truncated[0]?"\n[output truncated]\n":""));}}finally{if(p.isAlive())p.destroyForcibly();}
 }
 static String checked(List<String> args,int timeout)throws Exception{Result r=command(args,timeout,4096);if(r.timeout()||r.code()!=0)throw new IllegalStateException("Container command failed");return r.output();}
 static void delete(Path dir)throws IOException{try(var files=Files.walk(dir)){for(Path f:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}
 @PreDestroy void stop(){executor.shutdownNow();}
}
