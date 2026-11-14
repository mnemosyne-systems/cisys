/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Resolves usernames and subjects through the Keycloak admin API, using the cisys-backend service account. */
@ApplicationScoped
public class KeycloakUsers {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private final ObjectMapper json = new ObjectMapper();

    @ConfigProperty(name = "cisys.keycloak.url") String keycloakUrl;
    @ConfigProperty(name = "cisys.keycloak.realm", defaultValue = "cisys") String realm;
    @ConfigProperty(name = "cisys.keycloak.admin-client-id") String clientId;
    @ConfigProperty(name = "cisys.keycloak.admin-client-secret") String clientSecret;

    /** The subject ("id") of the user with this username, or null when there is no such user. */
    public String subjectOf(String username) {
        String body = request("GET", "/admin/realms/" + realm + "/users?username=" + encode(username) + "&exact=true", token());
        try {
            JsonNode users = json.readTree(body);
            if (!users.isArray() || users.isEmpty()) return null;
            JsonNode id = users.get(0).get("id");
            return id == null ? null : id.asText();
        } catch (Exception e) {
            throw new IllegalStateException("Keycloak returned an unreadable user list: " + e, e);
        }
    }

    /** The display name for a subject, or "unknown" when Keycloak cannot provide one. Never throws. */
    public String usernameOf(String subject) {
        try {
            JsonNode user = json.readTree(request("GET", "/admin/realms/" + realm + "/users/" + encode(subject), token()));
            JsonNode username = user.get("username");
            return username == null ? "unknown" : username.asText();
        } catch (Exception e) {
            Log.warnf("Could not resolve subject '%s' to a username: %s", subject, e.toString());
            return "unknown";
        }
    }

    private String token() {
        String form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret);
        String body = request("POST", "/realms/" + realm + "/protocol/openid-connect/token", null, form);
        Matcher matcher = ACCESS_TOKEN.matcher(body);
        if (!matcher.find()) throw new IllegalStateException("Keycloak refused the cisys-backend service account");
        return matcher.group(1);
    }

    private String request(String method, String path, String bearer) {
        return request(method, path, bearer, null);
    }

    private String request(String method, String path, String bearer, String form) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(keycloakUrl + path)).timeout(TIMEOUT);
            builder.method(method, form == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(form));
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            if (form != null) builder.header("Content-Type", "application/x-www-form-urlencoded");
            HttpResponse<String> resp = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new IllegalStateException("Keycloak answered HTTP " + resp.statusCode() + " for " + path);
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to Keycloak", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not reach Keycloak: " + e, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
