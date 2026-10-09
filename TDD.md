# TDD development trail

Production behavior was introduced after an observed failing test. The original implementation used `mvn -q test`, with one new test or one small refinement of an existing test per cycle, followed by the production change and a green suite. Compilation failures counted as red, as requested.

The rows summarize the observed red–green increments; the individual tests reside in matching layered packages under `src/test/java/com/ivanocj/cameras/availability/`.

| Area | Observed red | Production behavior introduced |
| --- | --- | --- |
| DID normalization | Missing `DeviceId` class | Strip whitespace and uppercase user input. |
| DID format/bounds | Assertions expecting rejection failed | Require DID segments, seven-byte prefix/suffix limits, unsigned 32-bit serials, and a 23-character DID limit. Each bound was added in a separate cycle. |
| HiChip UID | Missing `HichipUid` class | Port the algorithm from `uid.py` and reproduce `FFFF-158409-EBDAC`. |
| PPCS framing | Missing packet class/decode method, then malformed-input assertions failed | Encode `F1` frames; decode unsigned message types; reject false lengths, bad magic, and truncated headers. |
| Wire messages | Missing message codec/address parser | Match the C DID/request byte vector and the legacy sockaddr byte order. |
| Server configuration | Missing parser, then the decoded reference list differed | Parse hosts without EDT DNS and port the recovered decoder. |
| Configuration errors | Blank input was accepted; keyed-profile message was generic | Reject unusable input and identify unsupported keyed configurations. |
| Rendezvous start | Missing probe/transport/request/result types | Send hello and a DID-specific connection request. |
| Deadline | Fake clock remained at zero | Bound receive polls by the requested monotonic deadline. |
| Server health | Missing timeout status | Recognize a valid server response without marking the device reachable. |
| Direct reachability | Missing reachable status | Require advertised peer, DID-matched readiness, and alive acknowledgement. |
| Session close | Last packet was alive, not close | Close confirmed sessions; a later cycle added cleanup for partial sessions. |
| Packet loss | Only one connection/alive/punch packet was sent | Add retransmission independently for rendezvous, alive confirmation, and direct punching. |
| Cancellation | Missing cancelled status | Stop between bounded polls; later tests made cancellation during receive override a late success. |
| Malformed input | Probe threw on empty packets and malformed addresses | Ignore malformed frames/payloads without claiming confirmation. |
| LAN discovery | Result stayed inconclusive; discovery packet was absent | Handle matching LAN punches and send discovery on UDP 32108. |
| UDP relay | Missing relay status; dropped relay legs timed out | Implement the legacy relay sequence and retry each relay leg. |
| Result metadata | Result constructor did not carry metadata | Preserve server health, peer/public endpoints, details, and elapsed time. |
| Server rejection | Missing rejected status | Preserve raw wire statuses without treating them as power-off or native SDK codes. |
| Progress stages | Progress list was empty | Emit distinct server, DID connection, peer-ready, and confirmation stages. |
| Session ownership | A second ready packet replaced the first pending peer | Retain one owner for the selected session. |
| Cleanup failure | A close-send exception erased a confirmed result | Report cleanup failure while preserving the reachability evidence. |
| Java UDP adapter | Missing transport class; receive timeout threw | Exchange real datagrams and model a timeout as an empty poll. |
| Socket lifecycle | Missing runner class | Resolve endpoints and close each check's owned socket. |
| Partial DNS failure | One bad server aborted the runner | Continue with other resolvable servers. |
| DNS cancellation/deadline | Test futures timed out while DNS remained blocked | Poll a cancellable virtual-thread lookup and bound it by the overall deadline. |
| Elapsed time | Expected one second, reported 750 ms | Include resolution in the runner's reported elapsed time. |
| Swing submission | Missing panel class | Submit normalized DID/settings on a worker thread. |
| Result rendering | Missing status component | Render completed results on the EDT. |
| Busy form | Inputs stayed enabled | Lock the probe controls until worker completion/cleanup. |
| UI cancellation/shutdown | Missing cancel control/close method | Signal cooperative cancellation without prematurely completing the worker. |
| Validation/error UI | EDT exception, then failure details/state were absent | Display inline input/network errors and restore controls. |
| Settings and UID helper | Missing controls | Honor timeout/LAN choices; expose the converted UID helper and inline helper validation. |
| Result card | Relay label/metadata rendering was absent | Show mode separately from server health and endpoint/timing metadata. |
| Modern layout/accessibility | Controls shared one container; labels/heading were absent | Separate form/result cards, associate input labels, add a readable heading and distinct primary action. |
| Diagnostics/profile | Missing log/profile components | Stream read-only EDT diagnostics and identify the legacy protocol profile. |
| Fresh check state | Previous peer/network/details remained visible | Reset metadata and previous confirmation text when starting a new check. |
| Native-free theme | Missing theme installer | Install FlatLaf with readable fonts and native integration disabled. |
| Bootstrap/window/JAR entry | Missing application/window method or public main method | Bootstrap on the EDT, configure a usable frame, connect window close to cleanup, and expose the JAR entry point. |
| Real-socket whole probe | Missing default runner constructor | Wire the real Java resolver/UDP transport and complete a loopback DID/ready/alive/close exchange. |
| HIP2P server recovery | Missing catalog, then one missing profile per cycle | Add each of the six decoded SY/PPCS endpoint triples independently, preserving original configurations and DNS names. |
| Catalog defaults/display | Initial profile differed; display exposed encoded data; missing endpoint formatter | Retain the Bell reference first, display meaningful names, and format editable `host:port` lists. |
| Known-list UI | Missing selector, then it remained enabled during a probe | Populate the server input from a selected profile and lock the selector until completion. |
| XQ configuration | Missing decoder; unsupported suffix and false count were accepted | Decode uppercase pairs and validate `-$$` configurations; accept them through the common parser. |
| XQ wire codec | Missing protocol/packet builders; oversized IDs raised buffer overflow | Encode source-derived ASCII identity and connection/relay vectors; reject truncation. |
| Protocol requests | Missing selection accessor; invalid IDs were accepted | Preserve existing SY defaults and validate the selected protocol before network work. |
| XQ probe/runner | Direct check timed out; relay packets carried SY identity; DNS reconstruction lost XQ | Select wire identity/packets throughout the shared state machine and preserve the protocol in the runner. |
| XQ heartbeat/diagnostics | Selected protocol was absent; heartbeat ACK was not sent | Emit XQ stage/detail text and acknowledge the chosen peer's heartbeat. |
| XQ UI/presets | Profiles were absent; requests used PPCS; controls/description were missing or unlocked | Add three native deployments, transport metadata, explicit/automatic XQ selection, and busy locking. |
| Layered architecture | ArchUnit failed against the flat namespace and missing layers | Move production/unit packages into layers, move wire encoding out of the domain, inject application ports for configuration and I/O/session orchestration, and extract shared test doubles. Preserve the existing behavior tests. |
| Batch prefix metadata | Missing `DeviceId.prefix()` and `ServerProfile.prefixes()` accessors | Add complete normalized prefix extraction and explicit immutable prefix-to-deployment metadata for the recovered presets; leave the Bell reference unmapped. |
| Batch import/routing | Missing batch service/result classes | Read source-numbered UTF-8 lines, validate each DID/protocol, route exact prefixes to their servers/transport, skip unknown/ambiguous mappings, and retain independent failures and cancellation outcomes. |
| Batch desktop flow | Missing file-selection seam and batch UI | Import and check on a worker, publish incremental read-only EDT results/progress, preserve the single-device form, and retain busy locking through cleanup. |
| CSV serial descriptions | Missing `readCsv()` and `BatchEntry.description()` | Preserve serial strings/descriptions, validate headers and row shapes, and support standard quoting/BOM/multiline records with cancellable import. Public tests use a fabricated, test-only fixture; private inputs are not distributed. |
| Batch File menu | Missing `menuBar()` and menu-driven CSV flow | Attach a shared Open action to the File menu and batch button, support CSV/text selection, show descriptions in results, and lock both entry points during cleanup. |
| Returned P2P evidence | Missing `P2pInformation` and `ProbeResult.p2pInformation()` | Snapshot valid device/relay/server replies, raw lookup statuses, local endpoint, and connection mode; retain partial evidence on timeout/cancellation and preserve it through the runner. |
| P2P desktop display | Missing result-view P2P/device fields and formatting accessors | Show separate device/session/client endpoints, selectable P2P details, incremental batch address columns, and correct sorted-row identity without stale data or false confirmation. |
| Parallel batch scheduling | Missing parallelism-aware `check()` overload | Add bounded virtual-thread checks, immediate slot refill, serialized coordinator callbacks, source-ordered final results, and repeated-DID serialization with cooperative all-worker cleanup. |
| Parallel batch desktop flow | Missing `batchParallelism` control | Default to 8 concurrent checks, expose an accessible 1–16 control, report the limit in diagnostics/summaries, and retain EDT publication and busy locking through every active probe's cleanup. |
| PPCS serial aliases | Padded/unpadded copies of a PPCS serial overlapped | Serialize equivalent numeric PPCS identities without modifying imported serial strings; retain distinct full ASCII XQ identities. |
| Persistent logging/context | Missing `ApplicationLogging` and `LogContext` | Add SLF4J/Logback, private rotating UTF-8 files, bounded asynchronous writing, scoped IDs, console fallback, and shutdown/uncaught-error handling. |
| Use-case logging | Start/terminal events and worker correlation were absent | Log safe probe/batch outcomes, preserve error propagation, explicitly carry MDC into batch/DNS virtual threads, and omit device information from persisted events. |
| Extended PPCS peer replies | A source-layout LAN punch/ready fixture expected `REACHABLE_P2P` but returned `INCONCLUSIVE` | Recognize exactly 24-byte PPCS `0x41`/`0x42` replies alongside legacy 20-byte replies, matching the complete UID independently of trailing API-version/reserved metadata. Preserve endpoint trust, alive confirmation, XQ/relay widths, and outgoing formats. |
| Complete DNS answer sets | A two-address/two-port fixture retained only the first IPv4 answer | Preserve all distinct IPv4 endpoints and ports without changing protocol or timeout orchestration. |
| Independent LAN sessions | Empty/failed DNS raised setup errors; timed-out DNS never invoked a session | Permit no-server sessions only with LAN search, retain the remaining deadline, and select a broadcast route without requiring remote servers. |
| Yoosee identity isolation | Missing protocol-specific identifier types, parsers, and general request accessor | Add sealed composite/numeric/opaque identifiers, GWell namespace-bit keys, explicit numeric batch families, and guards against using the legacy codec for Yoosee. |
| GWell discovery codec | Missing codec/metadata contract | Reproduce the source's 1024-byte search vector and parse command-2 metadata with real datagram/header/extension bounds rather than fabricated padding. |
| GWell LAN lifecycle | Missing discovery adapter/port/result | Add independent UDP discovery, local-source filtering, retries, cooperative cancellation, bounded snapshots, per-interface send isolation, serialized fixed-listener ownership, and cleanup. |
| Yoosee backend evidence | Missing router, service, and distinct discovery/unsupported statuses | Route numeric GWell discovery independently, keep peer/alive/server evidence empty, and expose an explicit native-research-based unsupported remote boundary. |
| Yoosee desktop/batch flow | Blank LAN/Yoosee servers rejected; family-tagged entries skipped; discovery labelled no server response | Parse the selected family's identifier, add explicit empty-endpoint profiles, retain tagged batch routing, and render metadata without counting it as reachable. |

The batch feature used grouped import/routing and desktop tests in its red–green cycles. Additional regression coverage checks import cancellation, repeated codes/files, sorted row selection, full result metadata, and summary counts that never treat server responsiveness as peer reachability.

The P2P-information feature also used grouped model/probe and presentation red–green cycles. Additional regression tests verify metadata survives elapsed-time reconstruction and real PPCS/XQ loopback exchanges, relay addresses are never labelled device IPs, the last per-server status is retained, and snapshots never leak between probes.

A repeat full run encountered a local UDP fixture timeout. Both loopback listener tasks now signal startup before the probe deadline begins, and failed reachability assertions include returned network evidence. The subsequent clean verification passed, as did four successive standalone PPCS/XQ loopback runs.

Parallel scheduling and desktop behavior used grouped red–green cycles. Follow-up tests verify failure isolation, cancellation before submission, empty/skipped batches, coordinator interruption, and cleanup after unexpected completion/diagnostic/probe failures. Concurrency tests coordinate with latches rather than asserting wall-clock speedups. A real-socket regression requires PPCS and XQ clients to rendezvous simultaneously and verifies independent identities, addresses, and close packets. Existing order-sensitive desktop scenarios explicitly select one parallel slot for sequential compatibility.

An offline 100-row throughput check against the rebuilt executable JAR used a fake runner with a 100 ms wait per probe: **10.63 s sequential**, **1.40 s with 8 concurrent checks**, and **0.75 s with 16**. All 100 rows/callbacks were retained, source order was preserved, and observed active counts matched the limits. This is a scheduler benchmark, not live-device interoperability or server-capacity evidence. The clean verification passed **239 tests with zero failures/errors/skips**; the parallel service, Swing, and real-socket regressions also passed three successive standalone runs. Running/completed mixed-protocol views were rendered offline with eight simultaneous fake probes and restored controls.

Production logging used grouped backend/context and use-case red–green cycles. Follow-up tests verify UTF-8/UTC formatting, INFO-level preservation under the asynchronous overload policy, size rotation with gzip/retention, shutdown flushing, sanitized/bounded exception chains, shared-directory/lock/symlink safety, console fallback, and settings failures that do not prevent probes. The uncaught-error fixture initially read only the active file after its deliberately tiny limit caused rollover; its non-rotation assertion now uses a sufficient file budget, while the separate size/retention test continues forcing rollover. Separate-JVM fixtures exercise the real logging facade/backend and shutdown hook with 100 fake PPCS/XQ checks at DEBUG, retaining every outcome without persisting device identifiers, descriptions, or addresses. Test logging is explicitly isolated from real user log directories.

The final clean verification passed **259 tests with zero failures/errors/skips**. A separate smoke run against the shaded executable JAR preserved all 100 probe outcomes and session summaries, flushed on shutdown, and contained no fixture device data. The JAR has one SLF4J service provider, includes the production configuration but not the test configuration, and still contains no native binaries.

Build configuration, documentation, and test fixtures were added as supporting project files. Production source contains Java only. The tests have no dependency on the supplied native PPCS binaries or on an external camera/server deployment.

Extended peer-reply support began with one failing LAN exchange test against the unchanged probe, then passed after adding message/protocol-specific identity matching in infrastructure. Follow-up regressions exercise mixed legacy/extended direct and LAN replies, every UID byte including padding, malformed/truncated payloads, unexpected endpoints, XQ/relay isolation, cancellation, missing alive confirmation, and single-owner cleanup. The real UDP loopback fixture now covers both legacy and extended PPCS replies. The reference dissector is documentation only; its plugin/code is not a runtime or test dependency. The subsequent `mvn clean verify` passed **341 tests with zero failures/errors/skips** and rebuilt the executable JAR with no native binaries.

Yoosee and independent-LAN work used grouped red–green cycles for each contract/lifecycle/presentation increment; missing classes/methods were observed compilation failures. Regression tests additionally cover no-server expiry without false rejection, bounded immutable discovery snapshots, cancellation during a failed send, fixed-listener queue deadlines, per-family metadata, and an EDT-driven numeric-ID submission. Real-socket fixtures verify alive/close without servers or after failed DNS and GWell discovery metadata with listener-port release. All external traffic is replaced with loopback fixtures. Offline Ghidra investigation substantantiated the remote GWell/IoTVideo protocol boundary but did **not** implement or verify remote availability; [YOOSEE_NATIVE_ANALYSIS.md](YOOSEE_NATIVE_ANALYSIS.md) records the remaining work. Native libraries/reference tools are not runtime or test dependencies.

Review regressions first reproduced premature rejection of a valid 92-byte legacy discovery tail and socket setup after cancellation/deadline expiry during diagnostics or interface enumeration. The fixes require the optional final word only when advertised and re-check cancellation/deadlines before opening sockets. The historical `mvn -q clean verify` checkpoint passed **398 tests with zero failures/errors/skips**; archive checks confirmed that the new backends/codecs are present and native binaries/Ghidra research code are absent. The executable is now named `target/campulse-1.0.0.jar`. The shipped offline Ghidra helper also reproduced the selected list/status decompilations against the existing read-only project.

## Account-backed remote Yoosee follow-on

The 398-test checkpoint deliberately left remote availability unsupported. Follow-on work ports separate GWell and IoTVideo account sessions from current-library Ghidra evidence, with older demos used only for source correspondence. Protocol/configuration/loopback work used grouped increments, followed by the observed review red–green cycles below; this is not a claim that every new line was added in a one-test cycle.

| Area | Observed failure / review finding | Verified behavior |
| --- | --- | --- |
| Remote routing/catalog/UI | Existing tests expected unsupported profiles/results after production routing changed | Separate remote presence adapters, 51701 default ports, backend-specific account file selection, and explicit authentication/presence wording replace the old unsupported boundary. |
| RC5 and QuickLZ fixtures | A malformed hex fixture, then one failing QuickLZ assertion and one decoder error | Corrected synthetic fixtures, standard RC5 vectors at both native word widths, stored/literal/dictionary-match QuickLZ cases, and bounds/truncation regressions. |
| Reply evidence | Loopback assertion expected no evidence when GWell omitted the target | A correlated reply with no matching target retains `UNKNOWN`; ACK-only, wrong sequence/identity/target, false lengths/counts, and downgraded key mode never establish online/offline. |
| JSON overflow | An oversized integer wrapped into `OFFLINE` | Exact bounded numeric conversion prevents values outside int/long range from wrapping into presence/timestamps. |
| Account ownership | Grouped lifecycle regressions added for queued checks and cancellation | One backend registration owns its socket/key lifecycle through cleanup; queued callers use their own deadline and do not load/register competing accounts. A later check succeeds after cancelled cleanup. |
| LAN/remote independence | New tests failed with a listener `IOException` and a remote authentication failure hidden by LAN status | Configured remote checking proceeds after LAN setup failure; unauthenticated metadata remains separate and cannot hide authentication outcomes. |
| Presentation evidence | Expected account-credential wording, received “LAN only” with retained metadata | Authentication takes precedence over LAN metadata; an attempted remote check is not labelled LAN-only. Presence provenance/times are visible without a confirmed peer or an incremented reachable count. |
| Bootstrap-key length | After native cross-check, three independent wire-vector assertions failed at identity byte 4 | Both current SDKs explicitly pass 12 bytes to RC5. Removed the erroneous NUL from Java and regenerated independent list/init/certification vectors; loopback-only round trips would not expose this shared mistake. |
| Command rejection | Two loopback cases expected inconclusive, received `AUTH_FAILED` | Nonzero status-query delivery ACKs remain inconclusive, not invented account rejection or device-offline evidence. Registration failures remain separate. |
| GWell verification trailer | Native-derived loopback init-length assertion prevented the expected online result | Initial mode-1 `0x81` and RSA `0x05` append verification words and set the native flag; mode-2 init/status and command `0x03` retain their distinct lengths. |

Native review also corrected the IoTVideo certification to advertise MTU 1032: **164 bytes**, with the four-byte MTU included in the checksum and 1008-byte ordinary fragment bodies. The independent Python generator uses standard-library RC5/HMAC and system OpenSSL AES; tests embed its synthetic vectors, so neither Python nor OpenSSL is a build/runtime dependency.

Additional regressions cover nonblocking split/coalesced TCP framing, malformed lengths, partial-read cancellation/disconnects, every IPv4 answer/port, bounded/cancellable DNS, private-file size/symlink/errors/expiry, defensive account snapshots, fresh file reloads, ephemeral EDT file selection, busy locking, strict model lengths/terminators, duplicate JSON, timestamp freshness, out-of-order/duplicate fragments, assembly caps, and fragment-flag-specific sizes. One test-fixture refinement tried binding an unconfigured `127.0.0.2` alias on macOS and failed with `BindException`; reserving wildcard local UDP ports avoids that platform assumption while all generated traffic remains loopback.

The remote-adapter checkpoint's **`mvn -q clean verify` passed 468 tests, zero failures/errors/skips**. The executable is now `target/campulse-1.0.0.jar`. Remote lifecycle/DNS/TCP regressions also passed **three successive standalone runs**. Archive inspection confirmed both remote adapters, one SLF4J provider, and **zero native binaries or research scripts**. No physical camera or cloud service was contacted and no native SDK binary was executed. [YOOSEE_NATIVE_ANALYSIS.md](YOOSEE_NATIVE_ANALYSIS.md) documents the recovered flows, hashes, offsets, supported account formats, and remaining **authorized live-interoperability boundary**. Remote presence is implemented offline, not a claim of alive-confirmed camera reachability or current deployed-server compatibility.

## Public CamPulse snapshot

The public project begins with fresh Git history and excludes the private device inventory. Inventory-derived test values were replaced with fabricated identifiers; the 17-row synthetic CSV is test-only and never bundled. `PublicResourcesTest` verifies Maven's explicit runtime-resource allowlist. The renamed executable passes **580 tests with zero failures/errors/skips** under `mvn clean verify`, including the subsequent desktop workflow regressions. Filename guards, private-input ignore rules, and a clean secret scan supplement manual privacy review; they do not make arbitrary future content safe automatically.
