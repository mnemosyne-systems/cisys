/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCacheTest {
  @TempDir Path temp;

  private ProjectCache cache(long maxMib) {
    ProjectCache c = new ProjectCache();
    c.cacheDir = temp.resolve("cache").toString();
    c.maxMib = maxMib;
    return c;
  }

  @Test
  void keepsRelativePathsAndRejectsTheRest() {
    assertEquals(
        ".m2/repository\n.npm",
        ProjectCache.setting(List.of(" .m2/repository ", "", ".npm", ".npm")));
    assertNull(ProjectCache.setting(List.of()));
    assertNull(ProjectCache.setting(Arrays.asList((String) null)));
    for (String bad :
        List.of("/etc", "..", ".", "../x", "a/../b", "a/./b", "a//b", "a b", "a/", "$HOME/x")) {
      assertThrows(IllegalArgumentException.class, () -> ProjectCache.setting(List.of(bad)), bad);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProjectCache.setting(
                Collections.nCopies(21, "x").stream().map(x -> x + Math.random()).toList()));
  }

  @Test
  void aSavedCacheIsCopiedIntoTheNextBuild() throws Exception {
    ProjectCache c = cache(0);
    Path saved = Files.createDirectories(temp.resolve("saved/.m2/repository"));
    Files.writeString(saved.resolve("a.jar"), "one");
    assertTrue(c.save(7L, temp.resolve("saved")));

    Path next = Files.createDirectories(temp.resolve("again/.m2/repository"));
    Files.writeString(next.resolve("b.jar"), "two");
    assertTrue(c.save(7L, temp.resolve("again")));

    Path build = temp.resolve("build");
    c.copyIn(7L, build);
    assertEquals("two", Files.readString(build.resolve(".m2/repository/b.jar")));
    assertFalse(Files.exists(build.resolve(".m2/repository/a.jar")));
    c.copyIn(8L, temp.resolve("other"));
    try (var files = Files.list(temp.resolve("other"))) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void aCacheOverTheLimitKeepsTheOldOne() throws Exception {
    ProjectCache c = cache(1);
    Path small = Files.createDirectories(temp.resolve("small"));
    Files.writeString(small.resolve("x"), "old");
    assertTrue(c.save(1L, small));
    Path big = Files.createDirectories(temp.resolve("big"));
    Files.write(big.resolve("x"), new byte[1024 * 1024 + 1]);
    assertFalse(c.save(1L, big));
    assertEquals("old", Files.readString(c.path(1L).resolve("x")));
  }

  @Test
  void symbolicLinksAreCopiedAsLinksNotFollowed() throws Exception {
    ProjectCache c = cache(0);
    Path secret = Files.writeString(temp.resolve("secret"), "host file");
    Path saved = Files.createDirectories(temp.resolve("saved"));
    Files.createSymbolicLink(saved.resolve("link"), secret);
    assertTrue(c.save(3L, saved));
    Path build = temp.resolve("build");
    c.copyIn(3L, build);
    assertTrue(Files.isSymbolicLink(build.resolve("link")));
    assertEquals(secret, Files.readSymbolicLink(build.resolve("link")));
  }

  @Test
  void deleteRemovesTheCache() throws Exception {
    ProjectCache c = cache(0);
    Path saved = Files.createDirectories(temp.resolve("saved"));
    Files.writeString(saved.resolve("x"), "x");
    c.save(5L, saved);
    c.delete(5L);
    assertFalse(Files.exists(c.path(5L)));
  }
}
