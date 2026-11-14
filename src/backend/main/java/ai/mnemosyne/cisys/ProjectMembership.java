/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.Instant;

/** One row per member per project; exactly one OWNER row per project. */
@Entity
public class ProjectMembership extends PanacheEntity {
    public Long projectId;
    public String subject;
    @Enumerated(EnumType.STRING) public MembershipRole role;
    public Instant createdAt;

    public enum MembershipRole { MEMBER, OWNER }
}
