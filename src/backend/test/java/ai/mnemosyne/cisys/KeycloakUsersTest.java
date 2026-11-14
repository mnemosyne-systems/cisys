/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeycloakUsersTest {
    private HttpServer keycloak;
    private int tokenStatus = 200;
    private String usersResponse = "[{\"id\":\"sub-123\",\"username\":\"ahmed\"}]";

    @BeforeEach void start() throws IOException {
        keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloak.createContext("/realms/cisys/protocol/openid-connect/token", exchange -> {
            byte[] body = "{\"access_token\":\"abc\",\"expires_in\":60}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        keycloak.createContext("/admin/realms/cisys/users", exchange -> {
            String path = exchange.getHttpContext().getPath();
            String body = exchange.getRequestURI().toString().contains("/users/sub-123") ? "{\"username\":\"ahmed\"}" : usersResponse;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        keycloak.start();
    }

    @AfterEach void stop() {
        keycloak.stop(0);
    }

    private KeycloakUsers users() {
        KeycloakUsers users = new KeycloakUsers();
        users.keycloakUrl = "http://127.0.0.1:" + keycloak.getAddress().getPort();
        users.realm = "cisys";
        users.clientId = "cisys-backend";
        users.clientSecret = "secret";
        return users;
    }

    @Test void resolvesUsernameToSubject() {
        assertEquals("sub-123", users().subjectOf("ahmed"));
    }

    @Test void unknownUsernameResolvesToNull() {
        usersResponse = "[]";
        assertNull(users().subjectOf("ghost"));
    }

    @Test void resolvesSubjectToUsername() {
        assertEquals("ahmed", users().usernameOf("sub-123"));
    }

    @Test void refusedClientFailsLoudly() {
        tokenStatus = 401;
        assertThrows(IllegalStateException.class, () -> users().subjectOf("ahmed"));
    }
}
