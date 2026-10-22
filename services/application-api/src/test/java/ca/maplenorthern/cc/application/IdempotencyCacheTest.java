package ca.maplenorthern.cc.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyCacheTest {

  @Test
  void sameKeyConcurrentlyRunsTheActionOnceAndReplaysTheRest() throws Exception {
    IdempotencyCache cache = new IdempotencyCache(24, 1000);
    AtomicInteger runs = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<IdempotencyCache.Outcome>> results = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      Callable<IdempotencyCache.Outcome> call =
          () ->
              cache.execute(
                  "submit",
                  "k1",
                  "h",
                  () -> {
                    sleep(100);
                    return "r" + runs.incrementAndGet();
                  });
      results.add(pool.submit(call));
    }
    int replays = 0;
    for (Future<IdempotencyCache.Outcome> f : results) {
      assertThat(f.get().response()).isEqualTo("r1");
      if (f.get().replayed()) {
        replays++;
      }
    }
    pool.shutdown();
    assertThat(runs.get()).isEqualTo(1);
    assertThat(replays).isEqualTo(7);
  }

  @Test
  void differentKeysRunInParallel() throws Exception {
    IdempotencyCache cache = new IdempotencyCache(24, 1000);
    ExecutorService pool = Executors.newFixedThreadPool(16);
    long start = System.nanoTime();
    List<Future<IdempotencyCache.Outcome>> results = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      String key = "key-" + i;
      results.add(
          pool.submit(
              () ->
                  cache.execute(
                      "submit",
                      key,
                      "h",
                      () -> {
                        sleep(200);
                        return key;
                      })));
    }
    for (Future<IdempotencyCache.Outcome> f : results) {
      f.get();
    }
    pool.shutdown();
    long millis = (System.nanoTime() - start) / 1_000_000;
    // serialized this would take 16 x 200 ms = 3.2 s; in parallel (striped locks, some keys may
    // share a stripe) well under half of it
    assertThat(millis).isLessThan(1600);
  }

  @Test
  void anotherRequestUnderTheSameKeyIsAConflict() {
    IdempotencyCache cache = new IdempotencyCache(24, 1000);
    cache.execute("submit", "k", "h1", () -> "r");
    assertThat(cache.execute("submit", "k", "h2", () -> "x").conflict()).isTrue();
    assertThat(cache.execute("submit", "k", "h1", () -> "x").replayed()).isTrue();
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
