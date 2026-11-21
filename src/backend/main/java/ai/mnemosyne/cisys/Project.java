/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

/** A Git repository that cisys builds. */
@Entity
public class Project extends PanacheEntity {
  @Column(nullable = false, length = 100, unique = true)
  public String name;

  @Column(nullable = false, length = 2048, unique = true)
  public String repositoryUrl;

  @Column(nullable = false)
  public boolean privateRepository;

  @Column(nullable = false, length = 255)
  public String ownerSubject;

  /** The environment variable that holds a read-only token for a private repository. */
  @Column(length = 100)
  public String credentialRef;

  /** GITHUB or GITLAB, fixed when pull request builds are enabled. */
  @Column(length = 20)
  public String pullRequestProvider;

  /** The highest pull request number present when the feature was enabled; null means seed next. */
  public Integer pullRequestBaseline;

  /**
   * Paths relative to the build's home directory kept between builds, one per line; see {@link
   * ProjectCache}. Null keeps nothing.
   */
  @Column(length = 2100)
  public String cachePaths;
}
