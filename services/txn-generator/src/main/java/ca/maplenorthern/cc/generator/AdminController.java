package ca.maplenorthern.cc.generator;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Control surface of the generator. */
@RestController
@RequestMapping("/admin")
public class AdminController {
  private final GeneratorService service;

  public AdminController(GeneratorService service) {
    this.service = service;
  }

  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> start(
      @RequestParam(defaultValue = "20") double tps,
      @RequestParam(defaultValue = "1000") long total,
      @RequestParam(defaultValue = "0") double poisonRate,
      @RequestParam(defaultValue = "42") long seed) {
    if (tps <= 0 || total <= 0 || poisonRate < 0 || poisonRate > 1) {
      return ResponseEntity.badRequest().body(Map.of("error", "tps>0, total>0, 0<=poisonRate<=1"));
    }
    if (!service.start(tps, total, poisonRate, seed)) {
      return ResponseEntity.status(409).body(service.status());
    }
    return ResponseEntity.accepted().body(service.status());
  }

  @PostMapping("/stop")
  public Map<String, Object> stop() {
    service.stop();
    return service.status();
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return service.status();
  }
}
