# Pure Java XQ availability transport

The application supports the IPv4 UDP availability path of HIP2P's **SDK 1 / XQ** family. It shares the rendezvous, LAN, UDP-relay, deadline, retry, cancellation, and cleanup state machine with SY/PPCS, while selecting an XQ wire identity and decoding XQ server configurations. In the layered namespace `com.ivanocj.cameras.availability`, `domain.model.TransportProtocol` identifies/validates the family and `infrastructure.protocol.ProtocolMessages` owns the wire encoding; see [ARCHITECTURE.md](ARCHITECTURE.md).

## Usage

Select **HIP2P · XQ FFFF/HHHH/GGGG**, **HIP2P · XQ IIII/JJJJ/KKKK**, or **HIP2P · XQ XXXX/YYYY/ZZZZ** in **Known lists**. The UI selects **Transport: XQ** automatically. For custom comma-separated endpoints, explicitly select **XQ**. Pasting a supported encoded configuration ending in `-$$` also selects XQ when the check starts.

XQ DIDs must fit **20 ASCII bytes**. Invalid values are rejected before network work. The generic SY/PPCS DID limit remains 23 characters.

## Reference evidence

`PeerToPeer/README.md` describes the SY/PPCS 3.1.0 SDK. Its C samples are lifecycle references, and its API documentation reinforces separation of standard/RTOS platforms and network health from a DID connection. Its Android `PPCS_APIs.java` is a JNI wrapper; it is not a pure Java XQ implementation and is not used by this application. Its `P2P_StringEncDec.java` uses an `A`/`a` key-based encoding, not the XQ uppercase alphabet mapping.

The XQ-specific implementation was reconstructed from the HIP2P binary identified in [HIP2P_SERVER_ANALYSIS.md](HIP2P_SERVER_ANALYSIS.md):

| Unslid address | Reconstructed behavior |
| --- | --- |
| `0x1001B92B4` | `HI_XQ_P2P_Init` wrapper. |
| `0x10022EB60` | XQ initialization and server parsing. |
| `0x10023EC80` / `0x10023EAF0` | Split configuration components, decode the count/semicolon-separated hosts, use UDP 32100. |
| `0x1002408D0` / `0x1002411F0` | Initialize the uppercase pair table / `HI_DecStr` decoding. |
| `0x100232A00` | `F1 20 00 24`: 20-byte full ASCII DID plus 16-byte local address. |
| `0x100232B20` / `0x100232B50` | Punch / ready messages with a 20-byte ASCII DID. |
| `0x100232BC0` | Relay-list request with the same ASCII identity. |
| `0x100232C80` / `0x100232CE0` | Relay request / relay punch; copy opaque cookie bytes and client role. |
| `0x100232D20` | Relay-ready message with the ASCII DID. |
| `0x100232D50` / `0x100232D70` / `0x100232E40` | Alive, alive acknowledgement, and close frames. |
| `0x100234E00` / `0x100235160` | Compare all 20 identity bytes when processing punch/ready packets. |

### Configuration decoding

For an uppercase pair `XY`:

```text
index = (X - 'A') * 26 + (Y - 'A')
decoded character = (index % 94) + 32
```

The native encoder has multiple representations for printable characters. The decoder recovers, for example:

```text
3;52.221.17.159;123.56.143.156;52.8.0.180
```

`XqConfiguration` validates paired uppercase characters, the `-$$` suffix, a count of one to six hosts, matching entry count, and usable endpoint syntax. `$$` denotes the native no-prefix-constraint form. Other encoded prefix/range-constraint suffixes are not implemented and are rejected.

### Wire identity

Both implemented families use the `F1` envelope, a one-byte type, and a big-endian two-byte payload length. Shared legacy IPv4 address serialization uses a big-endian family, little-endian port, reversed IPv4 bytes, and padding.

SY identity is `prefix[8] + uint32 serial + suffix[8]`. XQ identity is the **full DID padded with zero bytes to 20 bytes**. For `FFFF-158409-EBDAC`:

```text
46 46 46 46 2d 31 35 38 34 30 39 2d 45 42 44 41 43 00 00 00
```

Relay cookies are kept byte-for-byte across port allocation, relay request, and relay punch. A ready message alone does not mark the device reachable: an alive acknowledgement from the selected, DID-matched peer is required. Peer-initiated heartbeats are acknowledged while confirmation is pending.

## Known XQ lists

All endpoints use UDP 32100. Original encoded configurations are preserved in `KnownServerProfiles.java`.

| Prefix group | Configuration file offset | Decoded endpoints | Hello responders in the snapshot |
| --- | --- | --- | --- |
| `FFFF` / `HHHH` / `GGGG` | `0x581FE9` | `52.221.17.159`, `123.56.143.156`, `52.8.0.180` | `123.56.143.156`, `52.8.0.180` |
| `IIII` / `JJJJ` / `KKKK` | `0x581F6C` | `47.94.37.184`, `120.76.28.153`, `47.89.48.227`, `47.88.23.67` | All except `120.76.28.153` |
| `XXXX` / `YYYY` / `ZZZZ` | `0x58204E` | `47.94.56.41`, `47.91.165.44`, `47.88.0.50` | All three |

Snapshot on **2026-10-01**: eight of ten endpoints returned valid `F1 01 00 10` server hello acknowledgements within four seconds with approximately one-second retries. This manual check sent no DID requests, credentials, or camera commands. Unanswered endpoints are unconfirmed, not proven offline. A hello response is server-health evidence, not device availability.

## Verification and scope

- Failing-test-first cycles cover decoding, malformed configuration/count rejection, ASCII identity, connection/relay byte vectors, protocol propagation, direct/relay handshakes, heartbeats, pre-network DID validation, presets, and Swing transport selection/locking.
- `LoopbackProbeTest` completes an XQ direct ready/alive/close exchange using real Java UDP sockets.
- An additional real-socket fixture completed XQ relay discovery, hello/port allocation, request/punch/readiness, alive confirmation, and close.
- Unit tests do not contact external services or load either SDK binary.

This is an availability-focused transport, not the complete XQ SDK. IPv6, wake-up, super-device/forwarding extensions, constrained configuration suffixes, camera authentication, command encryption, and media channels are outside the implementation. Physical-device interoperability still requires verification with a real DID and its deployment configuration.
