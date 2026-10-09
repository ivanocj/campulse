package com.ivanocj.cameras.availability.infrastructure.protocol;

import java.util.Optional;

/** Control messages of the legacy PPPP/PPCS UDP profile; the XQ transport reuses the same codes. */
public enum MessageType {
    HELLO(0x00),
    HELLO_ACK(0x01),
    P2P_REQUEST(0x20),
    P2P_REQUEST_ACK(0x21),
    LAN_SEARCH(0x30),
    PUNCH_TO(0x40),
    PUNCH_PACKET(0x41),
    P2P_READY(0x42),
    RELAY_LIST_REQUEST(0x67),
    RELAY_LIST(0x69),
    RELAY_HELLO(0x70),
    RELAY_HELLO_ACK(0x71),
    RELAY_PORT_REQUEST(0x72),
    RELAY_PORT(0x73),
    RELAY_REQUEST(0x80),
    RELAY_TO(0x82),
    RELAY_PUNCH(0x83),
    RELAY_READY(0x84),
    ALIVE(0xe0),
    ALIVE_ACK(0xe1),
    CLOSE(0xf0);

    private static final MessageType[] BY_CODE = new MessageType[256];

    static {
        for (var type : values()) {
            BY_CODE[type.code] = type;
        }
    }

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Optional<MessageType> of(int code) {
        return code >= 0 && code < BY_CODE.length ? Optional.ofNullable(BY_CODE[code]) : Optional.empty();
    }
}
