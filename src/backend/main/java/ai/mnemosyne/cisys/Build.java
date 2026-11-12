/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
/** A build. Its log is a large object in the unmapped logoid column, see {@link BuildLogs}. */
@Entity public class Build extends PanacheEntity {
 public Long projectId;
 public String requestedBy;
 public enum Status { QUEUED, RUNNING, PASSED, FAILED }
 @Enumerated(EnumType.STRING) public Status status;
 public Instant requestedAt;
 @Column(length=2048) public String repositoryUrl;
 public String revision;
 public String branch;
 public String image;
 @Column(length=4000) public String command;
 public String credentialRef;
 @Column(length=300) public String imageDigest;
 public Integer exitCode;
 @Column(nullable=false) public boolean logTruncated;
 /** Pinned builds are kept by retention cleanup. */
 @Column(nullable=false) public boolean pinned;
 public Instant startedAt;
 public Instant finishedAt;
}
