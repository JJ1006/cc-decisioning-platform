package ca.maplenorthern.cc.generator;

import ca.maplenorthern.cc.common.Topics;
import ca.maplenorthern.cc.common.events.ApplicationSubmittedPayload;
import ca.maplenorthern.cc.common.events.Envelope;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.Events;
import ca.maplenorthern.cc.common.web.CorrelationContext;
import ca.maplenorthern.cc.xom.Application;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Sends synthetic applications at a target rate, optionally mixing in poison messages. */
@Service
public class GeneratorService {
  private static final Logger LOG = LoggerFactory.getLogger(GeneratorService.class);
  private static final long TICK_MS = 100;

  private final KafkaTemplate<String, String> kafka;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "generator");
            t.setDaemon(true);
            return t;
          });

  private ScheduledFuture<?> task;
  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong poison = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private volatile long target;
  private volatile double tps;
  private volatile Instant startedAt;

  public GeneratorService(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  /**
   * Starts a run.
   *
   * @param tps target messages per second
   * @param total stop after this many messages (applications plus poison)
   * @param poisonRate share of messages that are poison, 0 to 1
   * @param seed random seed, so a run can be repeated
   * @return false when a run is already in progress
   */
  public synchronized boolean start(double tps, long total, double poisonRate, long seed) {
    if (isRunning()) {
      return false;
    }
    sent.set(0);
    poison.set(0);
    failed.set(0);
    this.tps = tps;
    this.target = total;
    this.startedAt = Instant.now();
    ApplicationFactory factory = new ApplicationFactory(seed, LocalDate.now());
    Random dice = new Random(seed ^ 0x9E3779B97F4A7C15L);
    double perTick = Math.max(1.0, tps * TICK_MS / 1000.0);
    double[] carry = {0};
    task =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                carry[0] += perTick;
                while (carry[0] >= 1.0 && sent.get() < target) {
                  carry[0] -= 1.0;
                  sendOne(factory, dice, poisonRate);
                }
                if (sent.get() >= target) {
                  LOG.info(
                      "generator finished: sent={} poison={} failed={}",
                      sent.get(),
                      poison.get(),
                      failed.get());
                  task.cancel(false);
                }
              } catch (RuntimeException e) {
                LOG.error("generator tick failed", e);
              }
            },
            0,
            TICK_MS,
            TimeUnit.MILLISECONDS);
    LOG.info(
        "generator started: tps={} total={} poisonRate={} seed={}", tps, total, poisonRate, seed);
    return true;
  }

  private void sendOne(ApplicationFactory factory, Random dice, double poisonRate) {
    long n = sent.incrementAndGet();
    String correlationId = CorrelationContext.newId();
    ProducerRecord<String, String> record;
    if (dice.nextDouble() < poisonRate) {
      poison.incrementAndGet();
      record =
          new ProducerRecord<>(Topics.SUBMITTED, "POISON-" + n, ApplicationFactory.poisonValue(n));
    } else {
      Application app = factory.next();
      Envelope<ApplicationSubmittedPayload> env =
          Envelope.of(
              EventTypes.SUBMITTED,
              correlationId,
              "txn-generator",
              new ApplicationSubmittedPayload(app));
      record = new ProducerRecord<>(Topics.SUBMITTED, app.getApplicationId(), Events.write(env));
    }
    record
        .headers()
        .add("correlationId", correlationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    kafka
        .send(record)
        .whenComplete(
            (r, e) -> {
              if (e != null) {
                failed.incrementAndGet();
                LOG.warn("send failed: {}", e.toString());
              }
            });
  }

  /** Stops the current run, if any. */
  public synchronized void stop() {
    if (task != null) {
      task.cancel(false);
    }
    kafka.flush();
  }

  public boolean isRunning() {
    return task != null && !task.isDone();
  }

  /**
   * @return counters of the current or last run
   */
  public Map<String, Object> status() {
    return Map.of(
        "running", isRunning(),
        "sent", sent.get(),
        "poison", poison.get(),
        "failed", failed.get(),
        "target", target,
        "tps", tps,
        "startedAt", String.valueOf(startedAt));
  }
}
