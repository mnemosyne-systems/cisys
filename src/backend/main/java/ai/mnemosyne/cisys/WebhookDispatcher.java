/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends pending webhook deliveries and retries failed ones with backoff. Rows are claimed with a
 * lease under {@code skip locked}, so a crash mid-send only delays a delivery and two instances
 * never send the same one at once. A delivery never affects its build.
 */
@ApplicationScoped
public class WebhookDispatcher {
  static final int MAX_ATTEMPTS = 5;
  static final int BATCH = 20;
  static final Duration FIRST_RETRY = Duration.ofSeconds(30);
  static final Duration LEASE = Duration.ofMinutes(2);
  static final Duration KEEP = Duration.ofDays(30);

  @Inject WebhookUrlValidator urls;
  @Inject WebhookTransport transport;

  /** What one attempt needs, read before the request so no transaction stays open meanwhile. */
  record Claim(Long id, String url, String secret, String event, String payload) {}

  /** A status code, or an error when there is none or it was not 2xx. */
  record Outcome(Integer status, String error) {
    boolean delivered() {
      return status != null && status >= 200 && status < 300;
    }

    /** A 4xx other than timeout and rate limiting will not change on retry. */
    boolean permanent() {
      return status != null && status >= 400 && status < 500 && status != 408 && status != 429;
    }
  }

  @Scheduled(
      every = "{cisys.webhooks.every}",
      delayed = "10s",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduled() {
    try {
      Instant now = Instant.now();
      while (dispatch(now) == BATCH) {
        now = Instant.now();
      }
      purge(Instant.now());
    } catch (RuntimeException e) {
      Log.error("Webhook delivery failed", e);
    }
  }

  /** Sends one batch of due deliveries. Returns how many were attempted. */
  int dispatch(Instant now) {
    List<Claim> claims = QuarkusTransaction.requiringNew().call(() -> claim(now));
    for (Claim c : claims) {
      Outcome outcome = send(c);
      QuarkusTransaction.requiringNew().run(() -> record(c.id(), outcome, Instant.now()));
    }
    return claims.size();
  }

  @SuppressWarnings("unchecked")
  private List<Claim> claim(Instant now) {
    List<Number> ids =
        WebhookDelivery.getEntityManager()
            .createNativeQuery(
                "select id from webhookdelivery where status = 'PENDING' and nextattemptat <= ?1"
                    + " order by nextattemptat, id limit ?2 for update skip locked")
            .setParameter(1, now)
            .setParameter(2, BATCH)
            .getResultList();
    List<Claim> claims = new ArrayList<>();
    for (Number id : ids) {
      WebhookDelivery d = WebhookDelivery.findById(id.longValue());
      ProjectWebhook hook = ProjectWebhook.findById(d.webhookId);
      d.nextAttemptAt = now.plus(LEASE);
      claims.add(new Claim(d.id, hook.url, hook.secret, d.event.code(), d.payload));
    }
    return claims;
  }

  Outcome send(Claim c) {
    WebhookUrlValidator.Target target;
    try {
      target = urls.validate(c.url());
    } catch (IllegalArgumentException e) {
      return new Outcome(null, e.getMessage());
    }
    byte[] body = c.payload().getBytes(StandardCharsets.UTF_8);
    long timestamp = Instant.now().getEpochSecond();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", "cisys-webhooks");
    headers.put(WebhookService.EVENT_HEADER, c.event());
    headers.put(WebhookService.DELIVERY_HEADER, String.valueOf(c.id()));
    headers.put(WebhookService.TIMESTAMP_HEADER, String.valueOf(timestamp));
    headers.put(WebhookService.SIGNATURE_HEADER, WebhookService.sign(c.secret(), timestamp, body));
    try {
      int status = transport.post(target, headers, body);
      return new Outcome(status, status >= 200 && status < 300 ? null : "HTTP " + status);
    } catch (IOException | RuntimeException e) {
      String message = e.getMessage();
      return new Outcome(
          null, e.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }
  }

  private static void record(Long id, Outcome outcome, Instant now) {
    WebhookDelivery d = WebhookDelivery.findById(id);
    if (d == null) {
      return;
    }
    d.attempts++;
    d.lastAttemptAt = now;
    d.responseStatus = outcome.status();
    d.error = outcome.error() == null ? null : AuditContext.clean(outcome.error(), 500);
    if (outcome.delivered()) {
      d.status = WebhookDelivery.Status.DELIVERED;
      d.nextAttemptAt = null;
    } else if (outcome.permanent() || d.attempts >= MAX_ATTEMPTS) {
      d.status = WebhookDelivery.Status.FAILED;
      d.nextAttemptAt = null;
    } else {
      d.nextAttemptAt = now.plus(backoff(d.attempts));
    }
  }

  /** 30 s, 2 min, 8 min, 32 min. */
  static Duration backoff(int attempts) {
    return FIRST_RETRY.multipliedBy(1L << (2 * (attempts - 1)));
  }

  /** Removes finished deliveries older than {@link #KEEP}; builds take theirs with them. */
  int purge(Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            (int)
                WebhookDelivery.delete(
                    "status <> ?1 and createdAt < ?2",
                    WebhookDelivery.Status.PENDING,
                    now.minus(KEEP)));
  }
}
