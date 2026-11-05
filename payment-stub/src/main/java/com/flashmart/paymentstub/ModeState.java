package com.flashmart.paymentstub;

import org.springframework.stereotype.Component;

/** The current mode. One immutable pair swapped atomically, so a request never sees one mode with another's delay. */
@Component
public class ModeState {

    public record Current(Mode mode, long delayMs) {
    }

    private volatile Current current = new Current(Mode.HEALTHY, 0);

    public Current get() {
        return current;
    }

    public void set(Mode mode, long delayMs) {
        this.current = new Current(mode, delayMs);
    }
}
