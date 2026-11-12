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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped public class BuildRunner {
 @Inject BuildLogs logs;
 @Inject RepositoryUrlValidator repositoryUrls;
 @ConfigProperty(name="cisys.build.log-max-bytes",defaultValue="1073741824") long logMaxBytes;
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
 @ConfigProperty(name="cisys.build.concurrency",defaultValue="1") int concurrency;
 @ConfigProperty(name="cisys.build.queue-limit",defaultValue="100") int queueLimit;
 private final ExecutorService executor=Executors.newCachedThreadPool();
 private final Map<Long,Completion> completions=new ConcurrentHashMap<>();
 record Completion(Long id,Build.Status status,String revision,Integer exitCode,String imageDigest,
                   boolean logTruncated,Instant finishedAt){}
 /** Keep the result until its transaction commits; retry persistence, never execution. */
 void complete(Completion result){
  completions.put(result.id(),result);
  retryCompletions();
 }
 private synchronized void retryCompletions(){
  for(Completion result:completions.values()){
   try{
    QuarkusTransaction.requiringNew().run(()->persistCompletion(result));
    completions.remove(result.id(),result);
   }catch(RuntimeException e){Log.errorf(e,"Could not save result for build %d; will retry",result.id());}
  }
 }
 /** Called inside a fresh transaction, also when retrying a failed commit. */
 void persistCompletion(Completion r){
  Build.update("status = ?1, revision = ?2, exitCode = ?3, imageDigest = ?4, logTruncated = ?5, finishedAt = ?6 where id = ?7 and status = ?8",
      r.status(),r.revision(),r.exitCode(),r.imageDigest(),r.logTruncated(),r.finishedAt(),r.id(),Build.Status.RUNNING);
 }
 private volatile boolean ready;
 private volatile boolean stopping;
 /** Serialize admission and claims in the database, including the capacity check. */
 static void lockQueue(){
  Build.getEntityManager().createNativeQuery("select 1 from pg_advisory_xact_lock(1128878931)").getSingleResult();
 }
 void recover(@Observes StartupEvent event){
  if(concurrency<1 || queueLimit<1)throw new IllegalArgumentException("Build concurrency and queue limit must be positive");
  List<Long> running=QuarkusTransaction.requiringNew().call(()->Build.<Build>list("status",Build.Status.RUNNING).stream().map(b->b.id).toList());
  for(Long id:running){
   Throwable failure=logs.appendLine(id,"Server restarted during build");
   if(failure!=null)Log.errorf(failure,"Could not append to the log of build %d",id);
   QuarkusTransaction.requiringNew().run(()->{
    Build b=Build.findById(id);b.status=Build.Status.FAILED;b.finishedAt=Instant.now();
    if(failure!=null)b.logTruncated=true;
   });
  }
  ready=true;
 }
 Build submit(Long projectId,String actor,String url,BuildResource.RunRequest request){
  return QuarkusTransaction.requiringNew().call(()->{
   lockQueue();
   if(Project.findById(projectId)==null)throw new jakarta.ws.rs.NotFoundException();
   if(Build.count("status",Build.Status.QUEUED)>=queueLimit)throw new WebApplicationException(jakarta.ws.rs.core.Response.status(429).type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON).entity(Map.of("error","Build queue is full. Try again later.")).build());
   Build b=new Build();
   b.projectId=projectId;b.requestedBy=actor;b.status=Build.Status.QUEUED;
   b.requestedAt=Instant.now();b.repositoryUrl=url;
   b.branch=request.branch();b.image=request.image();b.command=request.command();b.credentialRef=request.credentialRef();
   b.persist();return b;
  });
 }
 /** Committed RUNNING rows reserve slots before any task is handed to the executor. */
 Build claim(){
  return QuarkusTransaction.requiringNew().call(()->{
   lockQueue();
   if(Build.count("status",Build.Status.RUNNING)>=concurrency)return null;
   Build b=Build.<Build>find("status = ?1 order by requestedAt, id",Build.Status.QUEUED).firstResult();
   if(b==null)return null;
   int changed=Build.update("status = ?1, startedAt = ?2 where id = ?3 and status = ?4",
       Build.Status.RUNNING,Instant.now(),b.id,Build.Status.QUEUED);
   if(changed!=1)return null;
   Build.getEntityManager().refresh(b);
   return b;
  });
 }
 @io.quarkus.scheduler.Scheduled(every="1s",concurrentExecution=io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP)
 synchronized void dispatch(){
  if(!ready || stopping)return;
  try{
   retryCompletions();
   Build b;
   while((b=claim())!=null){
    Build task=b;
    try{executor.submit(()->execute(task));}
    catch(RejectedExecutionException e){
     QuarkusTransaction.requiringNew().run(()->Build.update("status = ?1, startedAt = null where id = ?2 and status = ?3",Build.Status.QUEUED,task.id,Build.Status.RUNNING));
     throw e;
    }
   }
  }catch(RuntimeException e){Log.error("Build queue dispatch failed; will retry",e);}
 }
 void execute(Build b){run(b.id,b.repositoryUrl,new BuildResource.RunRequest(b.branch,b.image,b.command,b.credentialRef));}
 /** For tests waiting for the queue to drain. */
 boolean hasPendingBuilds(){return QuarkusTransaction.requiringNew().call(()->Build.count("status in ?1",List.of(Build.Status.QUEUED,Build.Status.RUNNING))>0);}
 private void run(Long id,String url,BuildResource.RunRequest request){
  Path dir=null;String name="cisys-build-"+id,revision=null,status="FAILED",digest=null;Integer exit=null;boolean truncated=false;Throwable captureError=null;BuildLogs.Writer log=null;
  try {
   log=logs.open(id);
   // Checked again here because DNS can change after the project was saved.
   repositoryUrls.validate(url);
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
   digest=digest(request.image());
   Streamed result=stream(List.of(runtime,"start","--attach",name),600,log,logMaxBytes);
   captureError=result.readerError();
   if(result.truncated()){truncated=true;log.line("[output truncated after "+logMaxBytes+" bytes; "+(result.totalBytes()-logMaxBytes)+" further bytes discarded]");}
   if(result.timeout()){try{checked(List.of(runtime,"kill",name),15);}catch(Exception ignored){}log.line("Build timed out");}
   else {
    // The container's exit code, not the attach client's.
    exit=exitCode(name);
    if(exit==null)log.line("Could not read the container exit code");else status=exit==0?"PASSED":"FAILED";
   }
  } catch(IllegalArgumentException e){
   if(log!=null)log.line("Build failed: "+e.getMessage());else captureError=e;
  } catch(Exception e){
   if(log!=null)log.line("Build failed during checkout or container execution ("+e.getClass().getSimpleName()+"). Check the runner configuration.");
   else captureError=e;
  }
  finally {
   try{checked(List.of(runtime,"rm","-f",name),15);}catch(Exception ignored){}
   if(dir!=null)try{delete(dir);}catch(Exception ignored){}
   // A log failure must not stop the status update.
   try{
    if(log!=null){
     if(captureError==null)captureError=log.failure();
     if(captureError!=null)log.line("[log capture failed: "+captureError.getClass().getSimpleName()+"]");
     log.close();
     if(captureError==null)captureError=log.failure();
    }
   }catch(Throwable t){if(captureError==null)captureError=t;}
   if(captureError!=null){truncated=true;Log.errorf(captureError,"Log capture failed for build %d",id);}
   complete(new Completion(id,Build.Status.valueOf(status),revision,exit,digest,truncated,Instant.now()));
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
 String digest(String image){
  try{String d=checked(List.of(runtime,"image","inspect","--format","{{index .RepoDigests 0}}",image),15).trim();return d.length()<=300&&d.matches("\\S+@sha256:[0-9a-f]{64}")?d:null;}
  catch(Exception e){return null;}
 }
 Integer exitCode(String name){
  try{return Integer.valueOf(checked(List.of(runtime,"inspect","--format={{.State.ExitCode}}",name),15).trim());}
  catch(Exception e){return null;}
 }

 interface LogSink{void append(byte[] bytes,int off,int len);}
 record Streamed(int code,boolean timeout,boolean truncated,long totalBytes,Throwable readerError){}

 static Streamed stream(List<String> args,int timeout,LogSink sink,long maxBytes)throws Exception{return stream(args,timeout,sink,maxBytes,30_000,5_000);}
 /** Output beyond maxBytes, or after the sink fails, is still read and discarded so the process never blocks. */
 static Streamed stream(List<String> args,int timeout,LogSink sink,long maxBytes,long drainMillis,long forceMillis)throws Exception{
  Process p=new ProcessBuilder(args).redirectErrorStream(true).start();p.getOutputStream().close();
  InputStream raw=p.getInputStream();
  AtomicLong total=new AtomicLong();
  AtomicReference<Throwable> error=new AtomicReference<>();
  AtomicBoolean forced=new AtomicBoolean();
  Thread reader=Thread.ofPlatform().daemon(true).name("cisys-log-reader").start(()->{
   byte[] buf=new byte[8192];boolean sinkOk=true;int n;
   try{
    while((n=raw.read(buf))!=-1){
     long before=total.getAndAdd(n);
     int keep=(int)Math.max(0,Math.min(n,maxBytes-before));
     if(keep>0&&sinkOk){try{sink.append(buf,0,keep);}catch(Throwable t){error.compareAndSet(null,t);sinkOk=false;}}
    }
   }catch(Throwable t){if(!forced.get())error.compareAndSet(null,t);}
  });
  try{
   boolean done=p.waitFor(timeout,TimeUnit.SECONDS);
   if(!done){p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}
   reader.join(drainMillis);
   if(reader.isAlive()){
    // Something still holds the pipe open, such as a background child.
    forced.set(true);
    p.descendants().forEach(ProcessHandle::destroyForcibly);
    try{raw.close();}catch(IOException ignored){}
    reader.join(forceMillis);
   }
   Throwable err=error.get();
   if(err==null&&reader.isAlive())err=new IllegalStateException("Log reader did not stop");
   return new Streamed(done?p.exitValue():-1,!done,total.get()>maxBytes,total.get(),err);
  }finally{if(p.isAlive())p.destroyForcibly();}
 }

 static void delete(Path dir)throws IOException{try(var files=Files.walk(dir)){for(Path f:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}
 @PreDestroy synchronized void stop(){stopping=true;executor.shutdownNow();}
}
