package dev.usbdac;

/** One consumed attempt after Android Auto and the selected USB output become ready. */
final class OnceAfterConnection {
    enum Event { NONE, SCHEDULED, APPLY, DISCONNECTED }
    private boolean ready;
    private boolean attempted;
    private int port;
    private long readyAt;

    Event observe(boolean autoConnected, int usbPort, long now) {
        if (!autoConnected || usbPort == 0) {
            if (!ready) return Event.NONE;
            ready = false;
            attempted = false;
            port = 0;
            return Event.DISCONNECTED;
        }
        if (!ready || usbPort != port) {
            ready = true;
            port = usbPort;
            attempted = false;
            readyAt = now;
            return Event.SCHEDULED;
        }
        if (!attempted && now - readyAt >= 5000) {
            // Consume before calling any routing API, including when the operation fails.
            attempted = true;
            return Event.APPLY;
        }
        return Event.NONE;
    }
}
