package com.flashmart.paymentstub;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Local-only switch for the stub's mode. Not authenticated; never expose this port. */
@RestController
public class AdminController {

    public record ModeRequest(Mode mode, Long delayMs) {
    }

    private final ModeState modes;

    public AdminController(ModeState modes) {
        this.modes = modes;
    }

    @PostMapping("/admin/mode")
    public ResponseEntity<?> setMode(@RequestBody ModeRequest request) {
        long delay = request.delayMs() == null ? 0 : request.delayMs();
        if (request.mode() == null || delay < 0) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST",
                    "mode is required and delayMs must be >= 0"));
        }
        modes.set(request.mode(), delay);
        return ResponseEntity.ok(modes.get());
    }
}
