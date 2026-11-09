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
@Entity public class Build extends PanacheEntity {
 public Long projectId;
 public String requestedBy;
 public String status;
 public String revision;
 public String branch;
 public String image;
 @Column(length=4000) public String command;
 public String credentialRef;
 public Instant startedAt;
 public Instant finishedAt;
 @Column(length=131072) public String output;
}
