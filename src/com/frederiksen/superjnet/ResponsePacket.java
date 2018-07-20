package com.frederiksen.superjnet;

/**
 * Blueprint for response packets.
 *
 * @param <T> associated requested packet class (for cool type safety).
 */
public class ResponsePacket<T extends RequestPacket> extends Network.Packet {

}
