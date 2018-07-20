package com.frederiksen.superjnet;

/**
 * Blueprint for request packets.
 *
 * @param <T> associated response packet class (for cool type safety).
 */
public abstract class RequestPacket<T extends ResponsePacket> extends Network.Packet {
    private long streamInterval; /* 0 if disabled streams */

    public long getStreamInterval() {
        return streamInterval;
    }

    /**
     * Sets the requested stream interval.
     *   > 0 : request a stream
     *   = 0 : send a non-stream request
     *   < 0 : shutdown a stream
     *
     * @param streamInterval in milliseconds
     */
    public void setStreamInterval(long streamInterval) {
        this.streamInterval = streamInterval;
    }

    /**
     * Checks if the remotes can share streams given their
     * request packets.
     *
     * @param requestPacket
     * @return can share?
     */
    public boolean canShareStream(RequestPacket requestPacket) {
        return getClass().isInstance(requestPacket) &&
                streamInterval == requestPacket.getStreamInterval();
    }
}
