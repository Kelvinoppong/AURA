package com.aura.core.telemetry;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryRecorder recorder;

    public TelemetryController(TelemetryRecorder recorder) {
        this.recorder = recorder;
    }

    @GetMapping
    public TelemetryRecorder.Snapshot current() {
        return recorder.snapshot();
    }

    @DeleteMapping
    public void reset() {
        recorder.reset();
    }
}
