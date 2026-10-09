# Yoosee / GWell / IoTVideo compatibility and native evidence

Static investigation of the supplied Yoosee 6.36 `../decoded/` tree and companion `../GwellDemo/` and `../IoTVideo-Android/` projects, using **Ghidra 12.1.4 headless analysis and decompilation** on ARM64 libraries and JADX on the bundled IoTVideo Java classes. No live camera or recovered cloud server was contacted. Decompiled C, extracted SDK inputs, Ghidra projects, and logs remain outside this repository; the native libraries are not loaded, linked, or packaged by the application.

## Delivered scope and remaining boundary

| Planned work | State |
| --- | --- |
| Independent PPCS/XQ LAN checking | Implemented: blank server lists, failed DNS, and bounded DNS timeouts no longer prevent LAN checking. Alive confirmation and session cleanup remain mandatory. |
| GWell UDP-8899 discovery | Implemented separately: recovered request/reply layout, numeric identity/type/subtype/model/MAC metadata, validation of actual datagram lengths, retries, cancellation, bounded snapshots, and listener cleanup. |
| Identifier and backend routing | Implemented: composite `DeviceId`, numeric `GwellDeviceId`, opaque case-sensitive `IotVideoDeviceId`, explicit protocol dispatch, and family-tagged batch entries. |
| Remote Yoosee availability | **Pure-Java GWell and IoTVideo account-backed presence adapters implemented**, with native-derived authentication/framing, strict correlation, bounded I/O, and offline loopback verification. **Live interoperability remains unverified.** |
| Every resolved IPv4 address | Implemented for PPCS/XQ and remote Yoosee, retaining distinct address/port combinations. |

`DISCOVERED_LAN` means an unauthenticated matching discovery reply, not `REACHABLE_P2P`. `REPORTED_ONLINE`/`REPORTED_OFFLINE` mean correlated server presence, not a tested camera connection. Missing replies, unknown/stale properties, and `AUTH_REQUIRED`/`AUTH_FAILED` are not offline evidence. No real cloud/camera was probed, no native SDK binary was executed, and no recovered credentials are embedded.

## Inputs and reproducibility

| ARM64 file | SHA-256 |
| --- | --- |
| `../decoded/lib/arm64-v8a/libp2pav.so` | `240ef15c5045108163ef891148eea17520e1279865fae2c06b869acb3d5fbc29` |
| `../decoded/lib/arm64-v8a/libiotvideomulti.so` | `b4d6f72168b666f80b08ebdd2eb210f57b5fd8199d8b375963f2568c5e96900a` |
| `../decoded/lib/arm64-v8a/libgwmediaplayer.so` | `ed96f011e4b52878f85b3df7f8cf6b967f3471875f511dd8d5f94d07b8e7697a` |

The offline helper [research/ghidra/DumpFunctions.java](research/ghidra/DumpFunctions.java) selects named seed functions and bounded direct callees. It is a Ghidra script, **not application source**. With `GHIDRA_HOME` pointing to an installed Ghidra and `OUT` to a private scratch directory, from the project directory:

```sh
mkdir -p "$OUT/projects"
"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" gwell \
  -import ../decoded/lib/arm64-v8a/libp2pav.so \
  -analysisTimeoutPerFile 600 -max-cpu 4 \
  -scriptPath research/ghidra \
  -postScript DumpFunctions.java "$OUT/gwell.c" \
  'fgGetDevsStatus|p2pu_eif_getDevState|p2pc_unit_v2_init|init_v2_frm_listsrv|init_frm_query_friends|p2pu_v2_send_query_friend_req|gutes_on_respfrm_query_friend|gute_session_new|gutes_start_UpdateEncKeyReq|gutes_on_respfrm_UpdateEncKey' 2

"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" iotvideo \
  -import ../decoded/lib/arm64-v8a/libiotvideomulti.so \
  -analysisTimeoutPerFile 600 -max-cpu 4 \
  -scriptPath research/ghidra \
  -postScript DumpFunctions.java "$OUT/iotvideo.c" \
  'IoTVideoInnerInitializer_nativeRegister|IoTVideoInnerInitializer_nUpdateAccessToken|iv_init_frm_listsrv|iv_start_process_query_listsrv|MessageMgrInternal_nRefreshProperty' 3
```

For an existing project, use `-process <library-name> -noanalysis -readOnly` instead of importing again. Ghidra's addresses in the investigation used image base `0x00100000`; the ELF offsets below omit that base. Decompiled pointer types and structure names are hypotheses unless supported by named symbols/logging or the Android call site.

## GWell LAN discovery

Source correspondence:

- `../decoded/smali_classes4/ph/i.smali` (`LocalNetSearchProcessor.java`): fixed UDP **8899** receive socket; discovery requests sent to limited/directed local broadcast addresses, repeated approximately once per second.
- `../decoded/smali_classes4/qh/a.smali` (`LocalNetGDevInfo.java`): default big-endian Java `ByteBuffer` encoding and parsing.
- `../decoded/smali_classes4/com/jwkj/p2p/utils/e.smali` (`GwMsgKits.kt`): leading `0` sets identity bit 31.

The request is the source app's **1024-byte zero-padded buffer**: command `1`, error `0`, header length `28`, then zero flags/identity/type/subtype and padding. Replies use command `2`. The base fields are 32-bit words:

| Byte offset | Meaning used by the port |
| --- | --- |
| 0 | Command; only reply `2` is accepted |
| 4 | Error; only `0` is accepted |
| 8 | Declared length; at least 28 and no more than the received bytes |
| 12 | Flags |
| 16 | Numeric identity, including namespace bit 31 |
| 20 / 24 | Device type / subtype |
| 60 / 64 | MAC as separately reversed 2-byte and 4-byte words |
| 68 | Model when flag bit 0 is set |

Flag bit 6 selects a presence-controlled extension layout. Bits 0, 4, 7, and 8 require their actual advertised tail bytes; bit 8 controls MAC availability in that layout. Legacy tails require the fixed fields through byte 91, plus bytes 92–95 only when the presence word at 88 is `1`. The parser does **not** copy the app's unsafe forced 200-byte buffer limit, infer missing fields from padding, or use the discovery endpoint as a P2P/media port.

The default scanner broadcasts only on local IPv4 interfaces and accepts replies from private, loopback, or link-local IPv4 sources. Replies are not authenticated. Snapshots are capped at 256 identity/endpoint pairs and are immutable. The shared adapter serializes scans because devices may return to the fixed listener port; queue time counts against each caller's timeout. Another process owning UDP 8899 can prevent setup. Tests substitute loopback destinations and isolated ports rather than broadcasting to physical cameras.

`01234` maps to `0x800004D2`; `1234` maps to `0x000004D2`. They are different namespaces. `001234` and `01234` share a wire identity while preserving their original display strings. IoTVideo keys have no inferred GWell aliasing.

## Server references: not PPCS presets

`GSdkInitor$registerGSdk$1.smali` first reads saved **`P2P_HOSTLIST`**, then falls back to these GWell names in the source's order:

```text
p2p1.cloudlinks.cn, p2p3.cloud-links.net, p2p2.cloudlinks.cn, p2p4.cloud-links.net,
p2p5.cloudlinks.cn, p2p6.cloudlinks.cn, p2p7.cloudlinks.cn, p2p8.cloudlinks.cn,
p2p9.cloudlinks.cn, p2p10.cloudlinks.cn
```

`../decoded/smali_classes4/le/a.smali` (`ReleaseHostUrl.kt`) selects **`list.iotvideo.cloudlinks.cn`** in production; `com/jwkj/f.smali` also uses that host when registering IoTVideo. The debug-selected `test-iotvideo.list.cloudlinks.cn` and older `list.iotvideo.tencentcs.com` constants are not production presets.

The names are retained as immutable metadata in `YooseeServerReferences`. `KnownServerProfiles` now offers separate account-required remote profiles on **51701**, plus an empty-endpoint GWell LAN-only profile. They are never interpreted as PPCS UDP-32100 services. TCP infrastructure endpoints come only from solicited, identity-matched replies from a configured list endpoint; ports retain network byte order. Public list sources cannot refer the checker into private/loopback networks. The Java port implements UDP list discovery, not the native HTTP fallback.

## Native GWell remote path

These are decompiled functions in `libp2pav.so`, not deductions from strings alone:

| Symbol / ELF offset | Observed behavior |
| --- | --- |
| `p2pc_unit_v2_init` / `0x1C0CC` | Initializes a separate unit/session. Sets local port `0xCAA8` (51880) and remote list port `0xC9F5` (51701). These are native defaults, not PPCS deployment ports. |
| `init_v2_frm_listsrv` / `0x1DB20` | Builds a 36-byte `0x7F,0x2B` list request, incorporates client identity, calculates a check value, and calls `gute_frm_rc5_encrypt`. |
| `FUN_0011dfc8` / `0x1DFC8` | Sends the same binary list request over UDP or HTTP. HTTP uses `application/octet-stream` and `/gwellcloud/service/ListService/GetServiceList`. This is service discovery, not an HTTP camera-online API. |
| `p2pu_start_tcp_connect_p2psrv_v2` / `0x1FF80` | Starts a TCP connection to discovered P2P infrastructure and returns to list/DNS discovery on failure. |
| `gute_session_new` / `0x44E0C` | Creates session-specific RC5 contexts, including a bootstrap context and message/callback state. |
| `gutes_start_UpdateEncKeyReq` / `0x46FC8` | Builds a `0x7F,0x05` key-update request and RSA-encrypts a 32-byte value containing time, identity, and configured values. |
| `gutes_on_respfrm_UpdateEncKey` / `0x472B0` | Updates an RC5 context and encryption/session state after a response. |
| `fgGetDevsStatusFromP2PServerV2` / `0x4CF28` | Dispatches status queries into the existing initialized native unit. |
| `p2pu_eif_getDevState_from_p2psrv` / `0x2799C` | Requires non-null communication-session state, accepts 1–256 IDs, and queues a query rather than sending an isolated anonymous PPCS request. |
| `init_frm_query_friends` / `0x269BC` | Builds command `0x7F,0x2D`, includes client identity and target IDs, with `(count + 4) * 8` total bytes. |
| `p2pu_v2_send_query_friend_req` / `0x27500` | Splits targets into bounded groups and submits them through `gutes_add_send_pkt` with response callbacks/retry state. |
| `gutes_on_respfrm_query_friend_Resp` / `0x26B94` | Parses returned ID/status/type/subtype information and invokes the native status-update callback. It validates count-derived packet lengths. |

The Java adapter implements this remote status mechanism, including the registration/key transitions below. It is not compatible with `F1` PPCS/XQ framing. Native-derived mode-1/mode-2 behavior and synthetic fixtures do not establish which optional registration variants every deployed server requires. No live interoperability claim is made.

## Native IoTVideo path and cloud presence

In `libiotvideomulti.so`:

- `Java_com_jwkj_iotvideo_init_IoTVideoInnerInitializer_nativeRegister` / ELF `0x150CB8` reads the P2P URL and **access token**, builds registration parameters with the **access ID**, identity/port types, and invokes the initializer. Invalid token/URL inputs are explicit error paths.
- `..._nUpdateAccessToken` / `0x15178C` updates the registered token rather than selecting a PPCS hostname.
- `iv_init_frm_listsrv` / `0x1E9D80` builds a **40-byte** `0x7F,0x15` list request with a 64-bit identity, check value, and `iv_gute_frm_rc5_encrypt` call. This differs from both GWell's list request and PPCS framing.
- `MessageMgrInternal_nRefreshPropertyOfDevice` / `0x1553DC` is a native property-refresh entry point; its existence is not a public unauthenticated HTTP contract.

Android `impl_backstage_task/t_message/e.smali` processes **`ProReadonly._online`** values with their timestamps separately from `IoTVideoInitializerState.ONLINE`. SDK `ONLINE` is registration/readiness of the client, not evidence that a requested camera is alive. The Java adapter reports timestamped cloud presence separately and retains unknown/stale/authorization-failure outcomes.

## Companion demos: what they add

**Both demos help, but they cover different backends.** `GwellDemo/` documents GWell account/session inputs and status callbacks. `IoTVideo-Android/` additionally bundles an actual IoTVideo implementation, including a smaller, named native transport library. Shared GUTE/RC5 primitives make it useful for cross-checking both investigations; they do not make the account credentials, registration packets, or status commands interchangeable.

### GWell account/session call sites

- `../GwellDemo/app/build.gradle:95` delegates the P2P implementation to external **`com.p2p.core:p2p-core:0.4.4`**. The README dates that release to **2018-03-05**. The inspected local archives do not supply that implementation; the bundled `libLogCollect.so` is not a P2P library.
- `../GwellDemo/app/src/main/java/com/example/dansesshou/jcentertest/LoginActivity.java:128-189` obtains **`UserID`, `SessionID`, `SessionID2`, `P2PVerifyCode1`, and `P2PVerifyCode2`** from `HttpSend.login(...)`, saves them, and installs P2P/setting listeners. Its comment says the verification codes can change after another-location login or a server refresh.
- `../GwellDemo/app/src/main/java/service/MainService.java:24-48` passes those values to `p2pConnect(userId, sessionid1, sessionid2, code1, code2, 0)` and calls `p2pDisconnect()` on destruction. The session strings are parsed as Java `long` and then narrowed to 32-bit `int`; do not assume their displayed decimal form is already a signed 32-bit integer.
- The same five logical fields are still explicit in Yoosee 6.36's `../decoded/smali_classes3/com/jwkj/GSdkInitor$registerGSdk$1.smali:366-465`: `P2PInitParam.Builder` sets `userId`, `sessionId1`, `sessionId2`, `code1`, and `code2`. It also sets customer/password/application information. The later JNI/native dumps map the five account fields to certification offsets; application/customer-specific variants remain outside this minimal account port.
- `../GwellDemo/app/src/main/java/com/example/dansesshou/jcentertest/MainActivity.java:57-66,102-104` calls `getFriendStatus(...)` before starting the connection service, despite the README's documented `init -> login -> connect -> commands -> disconnect` ordering. That race is not a lifecycle to copy.
- `../GwellDemo/app/src/main/java/sdk/SettingListener.java:748` declares `vRetGetFriendStatus(int count, String[] contactIds, int[] status, int[] types, boolean isFinish)` but leaves it empty. The demo therefore does **not** establish numeric status semantics or prove camera reachability. Delivery ACKs, call acceptance, and connection-ready callbacks remain separate.

The sample app IDs/tokens and APK customer/password values are not authorization for a new client. None are copied into runtime configuration.

### Bundled IoTVideo SDK and Java flow

`../IoTVideo-Android/app/build.gradle:83-92` uses a local **`app/libs/iotvideo-release.aar`**, rather than only an external SDK coordinate. Its `classes.jar` contains `com.tencentcs.iotvideo` classes, **not** `com.p2p.core`. It includes ARM64 and ARM32 native libraries; the ARM64 transport implementation is separate from the larger JNI/media library.

| Research input | SHA-256 |
| --- | --- |
| `../IoTVideo-Android/app/libs/iotvideo-release.aar` | `e1c3e2f7edc3085a83031ebf0346920d7d8d2d398b197ca7ee6541fe31b201df` |
| AAR `classes.jar` | `809eec72643c4c7b42378a84d4cd1761f0ab63a877c5ca2de455cd916be3451a` |
| AAR `jni/arm64-v8a/libiotp2pav.so` | `f33012e1503b05ae1dd9716edefddd3219b7584ea21ced8c28c29a78073dda07` |
| AAR `jni/arm64-v8a/libiotvideo.so` | `3b8fc4d4cdc1edf21467b23883726b445044d3932de4bf65bf5930eae7610fb6` |

JADX recovers SDK **`1.3(1b0e)`**, build time **2020-11-12 16:08:37**, from `com/tencentcs/iotvideo/BuildConfig.java` in `classes.jar`. The transport's `iv_access_init` also logs a November 2020 build date. Its default release list URL is **`|list.iotvideo.tencentcs.com`**, unlike the production host selected by the supplied Yoosee 6.36 app. These older defaults must not replace current server metadata or imply current interoperability.

The useful source/decompiled call chain is:

```text
LoginManager.initIoTVideo(accessId, accessToken)
  -> IoTVideoSdk.register(long accessId, String accessToken)
  -> nativeRegister(accessId, token, p2pUrl, packedVersion, deviceType, language)
  -> iv_access_init / iv_access_init_param_cfg / iv_unit_init

DeviceListFragment.updateDeviceModel()
  -> MessageMgr.readProperty(deviceId, "", listener)
  -> nativeGetData(deviceId, path)
  -> ivm_get_gdm_data / giot_eif_get_gdm_data_object
  -> correlated ModelMessage and/or model notification
  -> DeviceModelHelper.isOnline(deviceId)
```

- `../IoTVideo-Android/app/src/main/java/com/tencentcs/iotvideodemo/accountmgr/login/LoginManager.java:185-195` registers the access ID/token, configures the account module, and installs a model listener.
- `../IoTVideo-Android/app/src/main/java/com/tencentcs/iotvideodemo/accountmgr/devicemanager/DeviceListFragment.java:429-456` requests the complete model using an empty path and caches the successful JSON response; failures schedule a later retry rather than confirming an offline device.
- `../IoTVideo-Android/app/src/main/java/com/tencentcs/iotvideodemo/accountmgr/devicemanager/DeviceModelHelper.java:12-22` reads **`ProReadonly._online.ctlVal`**, falling back to **`stVal`**, and compares it with `1`. It returns `false` for missing data; the compatibility port must retain **unknown/stale** rather than adopting that UI shortcut as evidence of offline state.
- Decompiled `MessageMgr` correlates nonzero response IDs with outstanding listeners, treats ID `0` as a model notification, has bounded request timeouts, and clears request/model state on unregister. Its `APP_LINK_ONLINE = 1` callback describes the **client's link to infrastructure**, not a selected camera's availability.
- `../IoTVideo-Android/accountmgrtc/src/main/java/com/tencentcs/iotvideo/http/TencentcsHttpServiceAdapter.java:746-826` supplies `DescribeBindDev` and `CreateAnonymousAccessToken`; `TencentcsSign.java` implements **TC3-HMAC-SHA256** signing. "Anonymous" describes the resulting user token, **not** an unsigned public API: calls still use authorized cloud SecretId/SecretKey credentials. `DescribeBindDevResponse` has device-binding metadata, not an online-status result. Cloud-signing credentials and SDK access ID/token are separate inputs.

### Additional Ghidra evidence from the demo AAR

Offsets below are ELF offsets, excluding the `0x00100000` Ghidra image base. Findings apply to this hashed **2020 demo SDK** unless separately corroborated in Yoosee 6.36.

| Library / symbol / ELF offset | Observed behavior |
| --- | --- |
| `libiotvideo.so` / `Java_com_tencentcs_iotvideo_IoTVideoSdk_nativeRegister` / `0x9FA10` | Copies registration inputs into a native config and invokes `iv_access_init`; the Java declaration supplies authoritative argument types. |
| `libiotvideo.so` / `..._nativeUpdateToken` / `0x9FD48` | Passes the replacement token to `iv_set_access_token`. |
| `libiotvideo.so` / `Java_com_tencentcs_iotvideo_messagemgr_MessageMgr_nativeGetData` / `0xB8154` | Calls `ivm_get_gdm_data` for a property/model read. |
| `libiotp2pav.so` / `iv_access_init_param_cfg` / `0x1EFC8` | Hex-decodes token material, configures the 64-bit identity, and incorporates token namespace/type information. Registration is not just an access-ID list request. |
| `libiotp2pav.so` / `iv_gute_session_new` / `0x2BB94` | Creates three 8-byte-block, 6-round RC5 contexts plus a 16-byte-block, 6-round context seeded from decoded token material. The identity/bootstrap context uses the same 12-byte initialization value as GWell's `gute_session_new`. |
| `libiotp2pav.so` / `iv_gutes_start_active_certify_req` / `0x2EFA8` | Builds **`0x7F,0x0C`** certification traffic, encrypts a 32-byte random value using the token-derived context, and queues response/ACK callbacks. This is not GWell's RSA `0x05` key-update flow. |
| `libiotp2pav.so` / `iv_init_frm_listsrv` / `0x32444` | Builds the same **40-byte `0x7F,0x15`** list-request layout seen in Yoosee 6.36: 64-bit identity, sequence, flags/check value, and RC5 encryption. Native structure offsets differ across versions. |
| `libiotp2pav.so` / `iv_unit_init` / `0x339D0` | Sets remote list port **`0xC9F5` (51701)** and creates a separate communication/session unit. A common port does not establish backend interchangeability. |
| `libiotp2pav.so` / `ivm_get_gdm_data` / `0x205A0` | Requires initialized communication state and resolves the supplied device string through `_find_dstid_from_tid_key_map` before calling the model-query builder. Opaque IDs cannot simply be cast to a 64-bit number. |
| `libiotp2pav.so` / `giot_eif_get_gdm_data_object` / `0x39E1C` | Builds **`0x7F,0xB7`** model/property traffic with target identity and path, retaining request/ACK/response and cached-model state. It submits through `iv_gutes_add_send_pkt`, not an isolated HTTP or PPCS online check. |
| `libiotp2pav.so` / `giot_get_gdm_data_object_resp` / `0x39138` | Correlates query sequence/target data, handles timeout/error callbacks, and updates or reads cached JSON containing `ProReadonly`, `ProWritable`, `Action`, and `ProConst`. Receipt alone does not establish property freshness. |

Ghidra produced the selected function dumps despite unrelated C++ exception-table/relocation diagnostics in the larger JNI/media library. Its inferred JNI/C++ signatures are not trustworthy contracts; use Java declarations, instructions, named callees, and cross-version evidence together. No native binary was executed.

To reproduce, extract only `classes.jar`, `jni/arm64-v8a/libiotp2pav.so`, and `jni/arm64-v8a/libiotvideo.so` from the AAR into a private `$OUT` directory, verify the hashes above, and run `jadx --no-res --no-imports -d "$OUT/java" "$OUT/classes.jar"`. With the same helper and Ghidra setup described earlier:

```sh
"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" iotvideo-demo-core \
  -import "$OUT/libiotp2pav.so" -analysisTimeoutPerFile 600 -max-cpu 2 \
  -scriptPath research/ghidra -postScript DumpFunctions.java "$OUT/core.c" \
  '^(iv_access_init|iv_access_init_param_cfg|iv_set_access_token|iv_unit_init|iv_init_frm_listsrv|iv_gutes_start_active_certify_req|iv_gute_init_frm_detect_req|giot_eif_get_gdm_data_object|gat_send_init_info_msg|iv_gute_frm_rc5_encrypt|iv_gute_session_new)$' 1

"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" iotvideo-demo-jni \
  -import "$OUT/libiotvideo.so" -analysisTimeoutPerFile 600 -max-cpu 2 \
  -scriptPath research/ghidra -postScript DumpFunctions.java "$OUT/jni.c" \
  '^Java_com_tencentcs_iotvideo_(IoTVideoSdk_(nativeRegister|nativeUpdateToken)|messagemgr_MessageMgr_nativeGetData)$' 1

"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" iotvideo-demo-core \
  -process libiotp2pav.so -noanalysis -readOnly -max-cpu 2 \
  -scriptPath research/ghidra -postScript DumpFunctions.java "$OUT/property.c" \
  '^(ivm_get_gdm_data|ivm_get_data_object_st|giot_get_gdm_data_object_resp|giot_get_gdm_data_object_ack|iv_gute_frm_init_chkval|rc5_ctx_new|rc5_ctx_setkey)$' 0
```

The older demos guided recovery; the Java implementation uses the additional current-library evidence below rather than assuming old demo defaults are still current. Both demos remain reference material, with no Android/native runtime dependency.

## Additional current-native evidence used by the Java port

Selected dumps in the private research directory are `gwell-jni.c`, `gwell-remote.c`, `gwell-list.c`, `iotvideo-remote.c`, `iotvideo-identities.c`, `iotvideo-auth.c`, `iotvideo-quicklz.c`, and `iotvideo-quicklz-hash.c`. Relevant **ELF offsets**:

| Library / symbol / offset | Ported evidence |
| --- | --- |
| `libgwmediaplayer.so` / `transformInitPrm` / `0x3EE68` | Named JNI fields put user ID at config `+0`, verification words at `+4/+8`, and session words at `+0x58/+0x5C`. |
| `libp2pav.so` / `gutes_start_CertifyReq` / `0x474D0` | Command `0x03`, length 44; `code1/code2` at wire 24/28, `sessionId1/sessionId2` at 32/36, initial RSA-modulus hash at 40. |
| `libp2pav.so` / `gutes_add_send_pkt` / `0x453B0` | Mode-1 non-LAN traffic except command `0x03` appends both verification words, sets bit 0 of byte 21, and increases length by 8. Required for initial `0x81` and RSA key update `0x05`; negotiated mode 2 does not append it. |
| `libp2pav.so` / `gute_session_new` / `0x44E0C` | Calls `rc5_ctx_setkey(..., "www.gwell.cc", 0x0C)`; **12 bytes, without NUL**. The 32-bit key schedule at `0x49314` copies exactly that supplied length. |
| `libp2pav.so` / `gute_frm_rc5_encrypt` / `0x43AA8` | Identity at 4 uses the public bootstrap key; header words at 12/16 and complete body blocks use per-frame mode 1 or negotiated mode 2. Unaligned tail bytes are not padded. |
| `libiotvideomulti.so` / `iv_gute_session_new` / `0x1A1310` | Base64-decodes the same 12-byte bootstrap constant and supplies length `0x0C`; token challenge uses the separate 16-byte-block context. |
| `libiotvideomulti.so` / `iv_gutes_add_send_pkt` / `0x1A2100` | Adds SNTP and token trailers, request correlation, HMAC-MD5, and AES-wrapped token material before sending. |
| `libiotvideomulti.so` / `iv_gutes_start_active_certify_req` / `0x1A4E00` | Command `0x0C`, 32-byte key challenge encrypted with token bytes 48–63, and advertised MTU 1032. |
| `libiotvideomulti.so` / `iv_gutes_on_respfrm_certify_resp` / `0x1A58C0` | Zero-result response installs the negotiated key and session identity from wire offset 28, then registers initialization information. |
| `libiotvideomulti.so` / `gat_send_init_info_msg` / `0x1F0B88` | Builds current `0x7E,0xA6` initialization; current logical length 62. |
| `libiotvideomulti.so` / `gat_rcv_init_info_msg_resp` / `0x1F714C` | `0xA7` carries count-bounded 28-byte identity records; each contains a 64-bit DID and 16-byte TID. |
| `libiotvideomulti.so` / `iv_find_dstid_from_tid_key_map` / `0x1E8420` | Short decimal IDs and account-mapped opaque keys differ; native `atoll` shortcuts are not adopted for malformed input. |
| `libiotvideomulti.so` / `giot_eif_get_gdm_data_object` / `0x223D88` | Current account model request uses `0x7E,0xB7`, target at 24, empty path and no partial-cache shortcut. |
| `libiotvideomulti.so` / `giote_AES_encrypt` / `0x19D978`, nonce expansion / `0x19DB80` | AES-CBC with the native IV and four 32-bit hashes of the 12-byte nonce; type-1 account token appendix is 80 bytes. |
| `libiotvideomulti.so` / `qlz_get_setting` / `0x214AC0`, decoder / `0x215B34` | QuickLZ level 2, 2048 hash buckets with four slots; fresh-block literal/match decoding is bounded in Java. |

The previously assumed NUL-terminated bootstrap key was corrected during final review: independent list/certification fixtures failed against the old Java constant at identity byte 4, then passed with the native explicit 12-byte key. Loopback peers alone would not expose a shared codec mistake. The same review recovered missing GWell mode-1 verification trailers and corrected the IoTVideo MTU advertisement/fragment size.

### Implemented registration and status flows

**GWell:** UDP `0x2B/0x2C` list discovery → TCP mode-1 `0x81` initialization (80 bytes with account trailer) → `0x03/0x04` certification → exponent-3 RSA/PKCS#1 v1.5 `0x05/0x06` key update (with trailer) → mode-2 `0x81` readiness → correlated `0x2D/0x2E` status. RSA modulus size is validated as 64 or 128 bytes with matching bit count. The 32-byte compatibility key contains the two-hour time bucket, client identity, verification words, and zero optional fields. Unknown deployment-specific application/customer registration variants are not guessed.

GWell status replies must match `32 + upgradedCount * 52 + offlineCount * 4` exactly, with bounded counts and no duplicate requested identity. A matching 52-byte record reports online only for native state bytes 2–31; other states or an explicit offline-bucket entry report offline. Omission reports `UNKNOWN`, never inferred offline. No device endpoint in the status record is promoted to a tested peer. The reply has no presence timestamp; the port records receipt time and `GWELL_STATUS_QUERY` provenance.

**IoTVideo:** token-derived account identity → UDP `0x15/0x16` list discovery → TCP mode-1 `0x0C/0x0D` certification → negotiated key and session identity → mode-2 `0x7E,0xA6/0xA7` registration/mappings → correlated `0x7E,0xB7/0xB8` full-model query. Certification is **164 bytes**: 64-byte base, 4-byte advertised MTU, 16-byte SNTP trailer, and 80-byte token appendix. The frame checksum excludes both trailers, includes MTU and token-encrypted challenge, and is included in the appendix HMAC. Token bytes 0–47 are AES-wrapped; bytes 48–63 seed the challenge/HMAC. These recovered primitives are legacy compatibility, not modern transport security.

IoTVideo model replies require the requested target, zero result, present-model flag, exact byte length, and final NUL. JSON parsing rejects duplicate/trailing data and bounds size/depth/numbers; integer overflow cannot wrap into a valid presence value. `ProReadonly._online.ctlVal` takes precedence over `stVal`. Only integer 0/1 with positive epoch-second `t` is presence evidence. Age over 120 seconds is `STALE`; more than five seconds in the future is `UNKNOWN`. Missing/invalid values are `UNKNOWN`. No cached property or SDK-ready event counts as camera reachability.

### Lifecycle, bounds, and credentials

- Account snapshots come from a user-selected UTF-8 regular properties file or `YOOSEE_CREDENTIALS_FILE`, capped at 16 KiB and rejecting symlinks. The [README](README.md#private-yoosee-account-credentials) defines the five GWell login fields and IoTVideo access ID/type-1 64-byte hex token. No API secrets from the APK/demos are used; cloud SecretId/SecretKey are not SDK account tokens.
- Known `expiresAt` values are checked before network work and before accepting presence. Missing credentials return `AUTH_REQUIRED`; registration rejection/known expiry returns `AUTH_FAILED`. A declined status command stays inconclusive, not an invented account failure or offline state.
- Each shared remote adapter owns one registration at a time, including cleanup. Queueing, concurrent bounded DNS, list discovery, connect, authentication, and query consume the caller's monotonic budget (up to 120 seconds). A command gets at most three transmissions and a five-second phase; a delivery ACK only stops retransmission, never substitutes for a required response.
- TCP uses cancellable nonblocking Java channels, bounded 50 ms polls, and fixed receive storage; frame limits are 1500 bytes for GWell and 10240 for IoTVideo. Native `0x70` fragments use 1128-byte GWell bodies and 1008-byte IoTVideo bodies for advertised MTU 1032 (1392 for the LAN flag). Reassembly caps total storage/assemblies and rejects inconsistent duplicate parts, counts, lengths, and checks.
- Identity, protocol byte, command, request sequence, key mode, target, counts, and presence freshness are separate validations. Mode downgrade, uncorrelated notifications, wrong targets, malformed compression, and missing replies cannot produce positive/negative presence.
- Presence is published only after owned TCP/key/fragment resources close; cancellation overrides a late result. Mutable account/key buffers are erased on a best-effort JVM basis. No raw packets, model JSON, file contents/paths, or credential fields enter logs/preferences.
- Optional GWell LAN metadata survives a remote failure independently. A failed LAN listener does not block configured remote checking, and LAN replies do not hide authentication outcomes. Neither path creates an alive-confirmed camera session.

### Reproduce the additional static checks and offline fixtures

The investigation used `GHIDRA_HOME=/opt/homebrew/Cellar/ghidra/12.1.4/libexec`. The following are read-only additional dumps against the projects imported above; import `libgwmediaplayer.so` into a separate `gwell-jni` project first:

```sh
"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" gwell \
  -process libp2pav.so -noanalysis -readOnly -max-cpu 2 \
  -scriptPath research/ghidra -postScript DumpFunctions.java "$OUT/gwell-remote.c" \
  '^(gute_session_new|gute_frm_rc5_encrypt|rc5_ctx_setkey|gutes_add_send_pkt|gutes_start_CertifyReq|gutes_start_UpdateEncKeyReq|gutes_on_respfrm_UpdateEncKey|init_frm_initInfo|gutes_on_respfrm_query_friend_Resp)$' 2

"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" gwell-jni \
  -import ../decoded/lib/arm64-v8a/libgwmediaplayer.so \
  -analysisTimeoutPerFile 600 -max-cpu 2 -scriptPath research/ghidra \
  -postScript DumpFunctions.java "$OUT/gwell-jni.c" '^(transformInitPrm|Java_com_jwkj_p2p_GwVideoSdkNative_nativeRegister)$' 0

"$GHIDRA_HOME/support/analyzeHeadless" "$OUT/projects" iotvideo \
  -process libiotvideomulti.so -noanalysis -readOnly -max-cpu 2 \
  -scriptPath research/ghidra -postScript DumpFunctions.java "$OUT/iotvideo-remote.c" \
  '^(iv_access_init_param_cfg|iv_gute_session_new|iv_gute_frm_rc5_encrypt|iv_gutes_add_send_pkt|iv_gutes_start_active_certify_req|iv_gutes_on_respfrm_certify_resp|gat_send_init_info_msg|gat_rcv_init_info_msg_resp|iv_find_dstid_from_tid_key_map|giot_eif_get_gdm_data_object|giote_AES_encrypt|qlz_get_setting|qlz_decompress)$' 2

python3 research/generate_yoosee_fixtures.py
mvn -q clean verify
```

The fixture generator uses standard-library RC5/HMAC plus system OpenSSL for one AES vector; it emits only synthetic list/init/certification data. Tests embed those vectors and do **not** need Python/OpenSSL/Ghidra. It validates Java wire consistency, not deployed-server compatibility. Synthetic Java TCP/UDP peers cover both registration flows, presence/unknown/stale/authentication outcomes, key-mode transitions, fragmentation/compression, cancellation, ownership, and peer closure. This remote-adapter checkpoint passed **468 tests, zero failures/errors/skips**; the current public snapshot's full verification is recorded in the [README](README.md#verification-and-tdd).

## Remaining interoperability boundary

1. Obtain authorized account inputs and a consented app/server traffic capture covering registration, current positive/negative/stale presence, disconnects, and expiry/superseded sessions.
2. Compare deployed framing/crypto/identity behavior against the Java adapters and test with an owned device. No such credentials, device, or capture were supplied, so this validation is not claimed.
3. Add only evidenced alternate application/customer/token flows or HTTP discovery fallback when required. Anonymous/device tokens, cloud token creation/login, media sessions, and camera P2P/alive checks are outside the current remote port.

Item 4 is now implemented offline, not live-certified. Ghidra, `../decoded/`, both demos, `../lansearch/`, and `../dissector/` remain research/reference inputs only and are never application dependencies.
