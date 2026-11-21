/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The files a project keeps from one build to the next, such as a local Maven repository. A build
 * copies the project's cache into its home directory before the steps run; after a passed build
 * that is not a pull request build, the cached paths replace the stored cache.
 */
@ApplicationScoped
public class ProjectCache {
  /** The most paths a project can cache. */
  static final int MAX_PATHS = 20;

  /**
   * A path relative to the build's home directory: segments of letters, digits, '.', '_' and '-',
   * none of them '.' or '..'.
   */
  static final Pattern PATH =
      Pattern.compile(
          "(?!\\.{1,2}(/|$))[A-Za-z0-9._-]{1,100}(/(?!\\.{1,2}(/|$))[A-Za-z0-9._-]{1,100}){0,9}");

  @ConfigProperty(name = "cisys.cache.dir")
  String cacheDir;

  @ConfigProperty(name = "cisys.cache.max-mib", defaultValue = "2048")
  long maxMib;

  private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

  /** The stored cache of a project. */
  Path path(Long projectId) {
    return Path.of(cacheDir).resolve(String.valueOf(projectId));
  }

  private ReentrantLock lock(Long projectId) {
    return locks.computeIfAbsent(projectId, id -> new ReentrantLock());
  }

  /** The paths a project's setting lists, one per line, blank lines left out. */
  static List<String> paths(String setting) {
    return setting == null
        ? List.of()
        : setting.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
  }

  /** Checks the paths a lead entered and returns them as the stored setting, or null for none. */
  static String setting(List<String> paths) {
    List<String> kept =
        paths == null
            ? List.of()
            : paths.stream()
                .map(p -> p == null ? "" : p.trim())
                .filter(p -> !p.isEmpty())
                .distinct()
                .toList();
    if (kept.size() > MAX_PATHS) {
      throw new IllegalArgumentException("At most " + MAX_PATHS + " cached paths are allowed");
    }
    for (String p : kept) {
      if (!PATH.matcher(p).matches()) {
        throw new IllegalArgumentException(
            "Cached paths are relative to the home directory, such as .m2/repository: " + p);
      }
    }
    return kept.isEmpty() ? null : String.join("\n", kept);
  }

  /** Copies the stored cache into {@code target}, which is created; symbolic links stay links. */
  void copyIn(Long projectId, Path target) throws IOException {
    Files.createDirectories(target);
    ReentrantLock lock = lock(projectId);
    lock.lock();
    try {
      Path source = path(projectId);
      if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
        copy(source, target);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Makes {@code saved}, the cached paths taken out of a build, the project's cache. Returns false,
   * keeping the old cache, when it is larger than {@code cisys.cache.max-mib}. {@code saved} is
   * moved, so it must be on the same filesystem as the cache directory or is copied.
   */
  boolean save(Long projectId, Path saved) throws IOException {
    if (maxMib > 0 && size(saved) > maxMib * 1024 * 1024) {
      return false;
    }
    Path dir = path(projectId);
    Files.createDirectories(dir.getParent());
    Path next = Files.createTempDirectory(dir.getParent(), projectId + ".next-");
    copy(saved, next);
    ReentrantLock lock = lock(projectId);
    lock.lock();
    try {
      Path old = dir.resolveSibling(projectId + ".old");
      if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
        BuildRunner.delete(old);
      }
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
      }
      Files.move(next, dir, StandardCopyOption.ATOMIC_MOVE);
      if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
        BuildRunner.delete(old);
      }
    } finally {
      lock.unlock();
    }
    return true;
  }

  /** Removes a project's cache. */
  void delete(Long projectId) {
    ReentrantLock lock = lock(projectId);
    lock.lock();
    try {
      Path dir = path(projectId);
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        BuildRunner.delete(dir);
      }
    } catch (IOException ignored) {
    } finally {
      lock.unlock();
    }
  }

  /** Copies a tree without following symbolic links, which are copied as links. */
  private static void copy(Path from, Path to) throws IOException {
    Files.walkFileTree(
        from,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(to.resolve(from.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(
                file,
                to.resolve(from.relativize(file).toString()),
                LinkOption.NOFOLLOW_LINKS,
                StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static long size(Path dir) throws IOException {
    long[] total = new long[1];
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            total[0] += attrs.size();
            return FileVisitResult.CONTINUE;
          }
        });
    return total[0];
  }
}
