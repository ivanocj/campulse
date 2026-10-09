# HIP2PClient 2.1.2 server recovery

## Result

Static disassembly and Objective-C metadata reconstruction recovered **six SY/PPCS server configurations containing 18 distinct endpoints**. They are available in the application's **Known lists** selector alongside the original Bell reference list.

A separate, bounded live check received valid PPCS hello acknowledgements from **seven of the 18 recovered endpoints**. All three servers in the `WWWW`/`TTTT`/`UUUU` group responded. These observations establish server-protocol responsiveness, not device availability.

## Binary provenance

- Input: `../HIP2PClient_2.1.2.app/Contents/MacOS/HIP2PClient`
- Bundle version: **2.1.2**, build **5** (`Contents/Info.plist`).
- Format: **Mach-O x86_64**, stripped native symbols; Objective-C method/class metadata remains.
- SHA-256: `12a94a9b48ee7867aee36b3d49425efab04b5512ef2171e5a3df0602c157b954`
- SDK source-path strings identify `HiP2PSDK20210512`.
- The executable was inspected, not launched or modified. It is not needed by the Java application or its tests.

The findings are a partial reconstruction of the relevant initialization code, not recovery of the complete original source. Tools used were `file`, `lipo`, `otool`, `nm`, LLVM `objdump`, `strings`, and the existing Java server-string decoder.

## Why these are P2P servers

Finding an IP string by itself was insufficient. The reconstruction followed the registration argument positions:

```text
FirstIntDict:P2p:Push:andsdk:Push2:pwd:Dic:
Add2def2:p2pstr:Push:V:Push2:pwd:
```

The encoded strings are passed as `P2p`/`p2pstr`. Many adjacent literal IP addresses are passed as `Push`/`Push2` and were therefore excluded from the PPCS catalog.

The SDK dispatcher at **`0x1001B6A62`** routes SDK flag **0** to the SY initializer at **`0x1001B7AFC`**, and flag **1** to the XQ initializer at **`0x1001B92B4`**. Nearby diagnostics identify these wrappers as `HI_SY_P2P_Init` and `HI_XQ_P2P_Init`.

Relevant Objective-C method implementations:

| Method | Address | Evidence |
| --- | --- | --- |
| `+[HiChipSDK initAsync:]` | `0x1001B6E53` | Registers the newer `WWWW`, `TTTT`, `UUUU` SY configuration and several separate XQ groups. |
| `+[HiChipSDK init]` | `0x1001B7663` | Initializes SDK 0 with the older SY configuration and SDK 1 with a separate `-$$` configuration. |
| `-[HiGetUid FirstW2Defaults]` | `0x100189061` | Registers older DID-prefix groups, including `AAAA`–`EEEE`, `AAES`, `DEAA`, `FDTAA`, and `SECRUI`. |

For this particular client, SDK 0 is SY/PPCS and SDK 1 is XQ/PPPP. This does not redefine the legacy `PPPP_*` aliases described in the original repository headers.

## Recovered configurations

Offsets below are **file offsets** of the encoded C strings. For these strings the unslid virtual address is `0x100000000 + offset`.

| Java selector entry / DID prefixes | Encoded-string offset | Decoded endpoints, UDP port 32100 | Hello replies observed |
| --- | --- | --- | --- |
| `HIP2P · SY WWWW/TTTT/UUUU` | `0x5858DE` | `47.91.222.210`, `47.93.34.139`, `47.254.79.165` | **3/3** |
| `HIP2P · SY AAAA–EEEE` (`AAAA`, `BBBB`, `CCCC`, `DDDD`, `EEEE`) | `0x581EFA` | `58.96.170.32`, `123.57.136.155`, `54.183.36.158` | **2/3**: `123.57.136.155`, `54.183.36.158` |
| `HIP2P · AAES` | `0x5820AC` | `190.210.197.185`, `190.210.197.181`, `190.210.197.184` | **1/3**: `190.210.197.184` |
| `HIP2P · Dericam DEAA` | `0x582120` | `52.8.254.146`, `p2p3.dericam.com`, `p2p2.dericam.com` | **1/3**: `p2p3.dericam.com` |
| `HIP2P · FDTAA` | `0x58218D` | `54.67.46.219`, `52.57.62.100`, `54.165.34.140` | **0/3** |
| `HIP2P · SECRUI` | `0x5821E5` | `47.91.94.133`, `47.89.252.25`, `101.37.148.156` | **0/3** |

The original encoded strings are preserved in `KnownServerProfiles.java`. The existing legacy decoder recovered syntactically valid endpoint lists from all six. The UI displays decoded `host:port` entries and keeps them editable. DNS names are retained rather than replaced with temporary resolved addresses.

### Live-check conditions

- Date: **2026-10-01**, from the development host's network.
- Sent only the **four-byte server hello** `F1 00 00 00`; no DID lookup, camera connection, credentials, or device commands were sent.
- Global receive window: **four seconds**, retrying unanswered destinations approximately once per second.
- Accepted only responses from resolved candidate addresses on UDP **32100**, with magic `F1`, type `01`, declared payload length **16**, and matching datagram length.
- Response signature: `F1 01 00 10 <16-byte reported address>`. The echoed client address is omitted from this report.
- `p2p3.dericam.com` resolved to `35.157.23.15` and replied; `p2p2.dericam.com` resolved to `34.196.55.206` without a reply in this window.
- As an existing-list control, `174.129.34.188:32100` also replied. `one.object-camera.com` and `four.object-camera.com` did not resolve during this check.

An unanswered endpoint is **unconfirmed**, not proven invalid or offline. Response observations are a dated snapshot. The catalog therefore stores source-confirmed configurations and does not hard-code permanent “online” labels or combine different DID deployments into one default list.

## Separate XQ family and excluded addresses

The client registers `FFFF`, `HHHH`, and `GGGG` with **SDK flag 1**, using a different encoded string at **`0x581FE9`** ending in `-$$`. Other flag-1 groups include `IIII`/`JJJJ`/`KKKK`, `XXXX`/`YYYY`/`ZZZZ`, and additional groups in `initAsync:`.

These configurations require the XQ decoder and ASCII-DID transport rather than the SY/PPCS wire identity. The application now includes a separate pure Java XQ selection and presets for the three groups above; see [XQ_TRANSPORT.md](XQ_TRANSPORT.md). A valid UID checksum alone does not determine the transport family, and SY server addresses are not substituted for an XQ deployment.

Examples of excluded **push** addresses are `47.75.171.122`, `47.91.149.233`, `47.90.64.173`, `190.210.197.178`, `52.52.228.148`, and `52.53.229.214`. Their argument bindings, not just their proximity to encoded strings, identify their role. NTP servers, multicast/broadcast addresses, and miscellaneous infrastructure strings were likewise not treated as PPCS rendezvous servers.

## Reproducing the static inspection

From the parent `cameras` directory:

```sh
file "HIP2PClient_2.1.2.app/Contents/MacOS/HIP2PClient"
shasum -a 256 "HIP2PClient_2.1.2.app/Contents/MacOS/HIP2PClient"
xcrun otool -ov "HIP2PClient_2.1.2.app/Contents/MacOS/HIP2PClient"
strings -a -t x "HIP2PClient_2.1.2.app/Contents/MacOS/HIP2PClient"
objdump -d --start-address=0x1001b6e53 --stop-address=0x1001b76d3 \
  --no-show-raw-insn "HIP2PClient_2.1.2.app/Contents/MacOS/HIP2PClient"
objdump -d --start-address=0x100189061 --stop-address=0x1001899f1 \
  --no-show-raw-insn "HIP2PClient_2.1.2.app/Contents/MacOS/HIP2PClient"
```

`infrastructure.configuration.ServerConfigurationParser.parse(profile.configuration())` reproduces the Java decoding. Tests cover each recovered triple, preservation of DNS names, display labels, editable endpoint formatting, profile selection, and locking the selector while a probe runs. Live server checks are deliberately not part of automated unit tests.
