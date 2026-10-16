package dev.usbdac;

public final class OnceAfterConnectionTest {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        OnceAfterConnection gate = new OnceAfterConnection();
        require(gate.observe(false,0,0) == OnceAfterConnection.Event.NONE,"Disconnected startup must not apply");
        require(gate.observe(false,10,500) == OnceAfterConnection.Event.NONE,"USB alone must not apply");
        require(gate.observe(true,10,1000) == OnceAfterConnection.Event.SCHEDULED,"AA connection with DAC schedules");
        require(gate.observe(true,10,5999) == OnceAfterConnection.Event.NONE,"Do not apply before five seconds");
        require(gate.observe(true,10,6000) == OnceAfterConnection.Event.APPLY,"Apply at five seconds");
        for (long now=6001; now<1000000; now+=1000)
            require(gate.observe(true,10,now) == OnceAfterConnection.Event.NONE,"Stable pair must never reapply");
        require(gate.observe(false,10,1000000) == OnceAfterConnection.Event.DISCONNECTED,"AA disconnect restores");
        require(gate.observe(true,10,1001000) == OnceAfterConnection.Event.SCHEDULED,"AA reconnect gets new delay");
        require(gate.observe(false,10,1002000) == OnceAfterConnection.Event.DISCONNECTED,"AA disconnect cancels timer");
        require(gate.observe(false,10,1010000) == OnceAfterConnection.Event.NONE,"Cancelled delay never fires");
        require(gate.observe(true,0,1020000) == OnceAfterConnection.Event.NONE,"AA without DAC waits");
        require(gate.observe(true,20,1021000) == OnceAfterConnection.Event.SCHEDULED,"DAC attached during AA schedules");
        require(gate.observe(true,20,1025999) == OnceAfterConnection.Event.NONE,"DAC attach needs full delay");
        require(gate.observe(true,20,1026000) == OnceAfterConnection.Event.APPLY,"DAC attach applies once");
        require(gate.observe(true,20,1030000) == OnceAfterConnection.Event.NONE,"No retry after simulated failed attempt");
        require(gate.observe(true,0,1031000) == OnceAfterConnection.Event.DISCONNECTED,"DAC detach restores");
        require(gate.observe(true,21,1032000) == OnceAfterConnection.Event.SCHEDULED,"DAC reconnect during AA schedules");
        require(gate.observe(true,21,1037000) == OnceAfterConnection.Event.APPLY,"DAC reconnect delay");
        require(gate.observe(true,22,1038000) == OnceAfterConnection.Event.SCHEDULED,"Selected DAC replacement schedules");
        require(gate.observe(true,22,1040000) == OnceAfterConnection.Event.NONE,"Replacement waits five seconds");
        require(gate.observe(true,0,1041000) == OnceAfterConnection.Event.DISCONNECTED,"Unplug cancels replacement timer");
        require(gate.observe(true,0,1050000) == OnceAfterConnection.Event.NONE,"Cancelled USB timer never fires");
        OnceAfterConnection startup = new OnceAfterConnection();
        require(startup.observe(true,30,900000) == OnceAfterConnection.Event.SCHEDULED,"Already-connected startup schedules");
        require(startup.observe(true,30,905000) == OnceAfterConnection.Event.APPLY,"Startup waits full five seconds");
        require(startup.observe(true,30,2000000) == OnceAfterConnection.Event.NONE,"No later call-route retry");
        System.out.println("PASS: AA/USB connect triggers, five-second delay, no periodic reapply, reconnect, replacement, timer cancellation, consumed failures, already-connected startup");
    }
}
