package ca.maplenorthern.cc.application;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Remembers the first response per Idempotency-Key (in memory: lost on restart, not shared between
 * replicas; see ADR-0010). The same key with the same request replays the response, with another
 * request it is a conflict. Calls with one key are serialized (striped locks), so two concurrent
 * requests with one key produce one event, while requests with different keys run in parallel: the
 * action is a Kafka transaction, and one global lock capped submissions at about 4 per second
 * (found by the k6 load eval).
 */
@Component
public class IdempotencyCache {

  /** A remembered response. */
  public record Stored(String requestHash, Object response, Instant at) {}

  /** Result of {@link #execute}: the response and whether it was replayed. */
  public record Outcome(Object response, boolean replayed, boolean conflict) {}

  private final Duration ttl;
  private final Map<String, Stored> entries;
  private final Object[] stripes = new Object[64];

  public IdempotencyCache(
      @Value("${api.idempotency-ttl-hours:24}") long ttlHours,
      @Value("${api.idempotency-max-entries:10000}") int max) {
    this.ttl = Duration.ofHours(ttlHours);
    for (int i = 0; i < stripes.length; i++) {
      stripes[i] = new Object();
    }
    this.entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Stored> eldest) {
            return size() > max;
          }
        };
  }

  /**
   * @param scope the operation, so one key can be used on different operations
   * @param key the Idempotency-Key header, or null for no idempotency
   * @param requestHash digest of the request
   * @param action produces the response the first time
   */
  public Outcome execute(String scope, String key, String requestHash, Supplier<Object> action) {
    if (key == null) {
      return new Outcome(action.get(), false, false);
    }
    String id = scope + "|" + key;
    synchronized (stripes[(id.hashCode() & 0x7fffffff) % stripes.length]) {
      Stored stored = lookup(id);
      if (stored != null) {
        return stored.requestHash().equals(requestHash)
            ? new Outcome(stored.response(), true, false)
            : new Outcome(null, false, true);
      }
      Object response = action.get(); // outside the map lock: only this key's stripe waits
      synchronized (entries) {
        entries.put(id, new Stored(requestHash, response, Instant.now()));
      }
      return new Outcome(response, false, false);
    }
  }

  private Stored lookup(String id) {
    synchronized (entries) {
      Stored stored = entries.get(id);
      if (stored != null && stored.at().plus(ttl).isBefore(Instant.now())) {
        entries.remove(id);
        return null;
      }
      return stored;
    }
  }
}
