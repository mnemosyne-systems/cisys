/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Replaces the real Keycloak admin calls in membership tests. */
@Alternative
@ApplicationScoped
public class TestKeycloakUsers extends KeycloakUsers {
    private static final Map<String, String> SUBJECTS = new ConcurrentHashMap<>();
    private static volatile boolean unavailable;

    static void reset() { SUBJECTS.clear(); unavailable = false; }
    static void unavailable() { unavailable = true; }
    static void user(String username, String subject) { SUBJECTS.put(username, subject); }

    @Override
    public String subjectOf(String username) {
        if (unavailable) throw new IllegalStateException("Keycloak answered HTTP 503 for /admin/realms/cisys/users?username=" + username);
        return SUBJECTS.get(username);
    }

    @Override
    public String usernameOf(String subject) {
        for (Map.Entry<String, String> e : SUBJECTS.entrySet()) if (e.getValue().equals(subject)) return e.getKey();
        return "unknown";
    }
}
