# UX rescue — proposed roadmap

**Status: approved on 2026-10-08. R0–R6 implemented, verified, and merged. R7 verified and pushed as `6a22f83` on `ux-rescue/r7-edt-responsiveness`; not yet merged. R8 confined characterization/measurement completed and verified on `ux-rescue/r8-preferences-latency`, based on R7; not yet merged. On 2026-10-09 the user chose to keep current persistence timing; the conditional async optimization is skipped. Native-store latency remains unmeasured and no native-store access is authorized.** Findings and snapshot source citations are in [01-analysis.md](01-analysis.md). Results and reproduction steps are in [03-implementation.md](03-implementation.md).

Citation shorthand: **`M/` = `src/main/java/com/ivanocj/cameras/availability/`**; **`T/` = `src/test/java/com/ivanocj/cameras/availability/`**. New test files/helpers below are explicitly proposals, not claims about existing code.

## 1. Priorities and guardrails

Impact/effort is an ordinal prioritization heuristic: impact H/M/L = 3/2/1; effort S/M/L = 1/2/3. It is not a financial ROI or measured usage estimate. R0 is a mandatory gate. Ties favor the inferred central single-check workflow, then batch work; conditional performance work is last.

| Order | Item | Impact | Effort | Impact ÷ effort | Risk |
|---|---|---|---|---|---|
| **R0** | Establish a confined green baseline and fill characterization gaps | Gate | S–M | Required first | Low; environment blocker |
| **R1** | Explain device-family, routing scope, and file/account requirements | H | S | 3.0 | Low |
| **R2** | Make result identity and cancellation state unmistakable | M | S | 2.0 | Low |
| **R3** | Finish keyboard access for existing actions | M | S | 2.0 | Low |
| **R4** | Reduce form density and adapt the existing window to small displays | H | M | 1.5 | Medium: layout/focus |
| **R5** | Put validation feedback beside the relevant field | M | M | 1.0 | Medium: validation precedence |
| **R6** | Make batch results easy to search and sort correctly | M | M | 1.0 | Medium: view/model selection |
| **R7** | Profile and, only if needed, smooth EDT update bursts | M | M | 1.0 | Medium: event ordering |
| **R8** | Remove measured Preferences stalls, only after agreeing persistence timing | M | M | 1.0, conditional | Medium; extra approval |

Stay with **Java 21 + Swing**. Keep existing FlatLaf; it is already configured and shipped (`pom.xml:49–52,99–106`; `M/presentation/swing/AvailabilityTheme.java:25–30`). Adding another look-and-feel or layout dependency would not address the demonstrated problems. Use JDK layouts/components. Verify HiDPI on real displays rather than claiming it from headless tests.

### Contracts that every item must preserve

- Single checks use visible/effective settings; encoded configuration can override transport. Batch routes per catalog identity, not the current single-check server list. `M/presentation/AvailabilityPresenter.java:46–60`; `M/application/service/BatchAvailabilityService.java:397–440`.
- Do not change Bell/default profile, timeout 10 (1–120), LAN default true, concurrency 8 (1–16), port defaults, route ambiguity/skipping, or import-then-test behavior. `M/presentation/swing/AvailabilityPanel.java:95–106,174–177,529–560`; `M/infrastructure/configuration/ServerConfigurationParser.java:37–58`; `M/application/service/BatchAvailabilityService.java:414–425`.
- Keep GWell leading zeros, IoTVideo case sensitivity, and PPCS/XQ identity distinctions. `M/domain/model/GwellDeviceId.java:5–20`; `M/domain/model/IotVideoDeviceId.java:5–11`; `M/domain/model/TransportProtocol.java:32–39`.
- Preserve all status/evidence distinctions and reachable-count rules. Never substitute relay/client addresses for camera IPs. `M/presentation/AvailabilityPresenter.java:129–143,168–204,276–298`.
- Cancel/close signal existing tokens and wait for owned cleanup. Do not replace this with `SwingWorker.cancel(true)` or early control unlocking. `M/presentation/swing/AvailabilityPanel.java:192–196,302–305,504–505,617–618`; `M/application/service/BatchAvailabilityService.java:332–346`.
- Keep edited-server replacement confirmation and the unchanged single form when inspecting a batch row. `M/presentation/swing/AvailabilityPanel.java:391–422`; `T/presentation/swing/P2pAvailabilityPanelTest.java:124–143`.
- Do not save, log, or reveal account paths/content. Do not weaken redaction or add real-server tests. `M/infrastructure/configuration/FileYooseeCredentials.java:17–66`; `T/presentation/swing/YooseeAvailabilityPanelTest.java:31–89`.

## 2. R0 — minimum safety net before refactoring

**Initial blocker, now resolved:** both Phase 1 confined Maven commands failed resolving dependency plugin 3.8.1. After explicit approval for Maven Central downloads into the repository-contained cache, compilation, all 482 tests, and packaging pass. See analysis §3 for the initial attempts and [R0 results](03-implementation.md#r0--test-safety-net) for current verification.

### Establish the baseline

1. Obtain required plugins/dependencies in a **repository-contained Maven repository**, without examining external caches/configuration. Agree how artifacts will be supplied before retrying. Keep explicit local settings and temporary paths.
2. Make tests repository-confined **before running the full suite**: use a new, test-only in-memory `PreferencesFactory`/`AbstractPreferences` implementation for real Preferences calls; configure the forked test JVM's `user.home`, `java.io.tmpdir`, and factory in `pom.xml`. Keep JUnit temporary files and subprocess log/output paths under `target/`. Do not rely on `java.util.prefs.userRoot` alone on macOS. Production Preferences behavior remains unchanged. Motivation: `T/presentation/swing/PreferencesFormMemoryTest.java:20–34`; `M/bootstrap/AvailabilityApplication.java:61–63`; `T/integration/logging/LoggingProcessTest.java:15–24,65–76`.
3. Run a fresh compile, all existing tests, and package; preserve failures as baseline evidence rather than fixing unrelated behavior in a UX commit. Do not run the optional installer or production main against real Preferences/network.

**Files:** `pom.xml`; new test-only helper under `T/testsupport/`; existing bootstrap/Preferences tests only if necessary to support isolation. Build settings/cache/output stay under ignored `target/`.

### Reuse, do not duplicate, current characterization coverage

| Critical contract | Existing tests to retain |
|---|---|
| Single request, off-EDT work, EDT rendering, locked controls, cancellation, invalid input, failure recovery | `T/presentation/swing/AvailabilityPanelTest.java:44–189`. |
| First-run reference route, manual XQ, encoded overrides, server-edit confirmation | `T/presentation/swing/AvailabilityPanelTest.java:266–282,419–515,630–660`; `T/presentation/AvailabilityPresenterTest.java:29–43`. |
| Generated HiChip DID, remembered form, default-button wiring | `T/presentation/swing/AvailabilityPanelTest.java:192–213,575–617`. |
| CSV/TXT import, skip rules, descriptions, source lines, malformed headers/quotes | `T/application/service/BatchCsvImportTest.java:27–107`; `T/application/service/BatchAvailabilityServiceTest.java:30–84,113–137`. |
| Batch routing ignores single form; menu/button share action; sorted row inspection | `T/presentation/swing/BatchAvailabilityPanelTest.java:49–104,282–310,347–404`. |
| Bounded parallelism, duplicate/alias identities, source order, cleanup before unlock | `T/application/service/ParallelBatchAvailabilityServiceTest.java:37–245`; `T/presentation/swing/ParallelBatchAvailabilityPanelTest.java:141–204`. |
| Discovery/presence are not reachable; device/relay/client IPs stay distinct | `T/presentation/YooseePresenterTest.java:48–115`; `T/presentation/AvailabilityPresenterTest.java:140–203`; `T/presentation/swing/P2pAvailabilityPanelTest.java:85–143`. |
| Account selection cancellation/clear/locking and no persisted path | `T/presentation/swing/YooseeAvailabilityPanelTest.java:31–89`. |
| Architecture stays toolkit-independent below presentation | `T/architecture/LayeredArchitectureTest.java:15–42`. |

### Add only the missing protection needed for this rescue

- **Paired single/batch routing characterization:** same supported DID with deliberately customized single-check servers; assert single request uses those servers while batch still uses catalog routing. Include tagged Yoosee IDs, leading zeros, case-sensitive keys, and unknown prefixes. Extend existing panel/service tests; capture actual `ProbeRequest`s, not only captions. Current split: `M/presentation/swing/AvailabilityPanel.java:458–467,529–560`; `M/application/service/BatchAvailabilityService.java:397–440`.
- **Result-context characterization:** single result → batch summary → selected sorted row → another single check. Record request identity, displayed evidence, unchanged single form, and clearing of stale endpoints. Cover an attempted row selection while busy; do not accidentally make it an executable-device selection. Current handlers: `M/presentation/swing/AvailabilityPanel.java:306–310,469,649–660`.
- **Direct table-model characterization** in a proposed `T/presentation/swing/BatchResultsTableModelTest.java`: repeated IDs retain distinct source lines/descriptions, original twelve values remain unchanged, missing/queued/skipped/cancelled metadata is represented correctly. Existing implementation: `M/presentation/swing/BatchResultsTableModel.java:15–83`.
- **Unit tests before each extraction:** request-validation precedence/field attribution for R5; filtering/sort keys and empty selection for R6; operation/progress ordering for any R7 helper. Keep helpers toolkit-independent where practical and within the existing layer contract. Do not introduce a new application architecture first.

**Verification:** all existing + new tests green on Java 21 in the confined setup; no production servers/private account files; no repository-boundary violations. Use fakes/latches already employed by panel tests instead of timing sleeps. Gate fails if full compile/test remains unavailable.

**Manual-check boundary:** use a repository-local, test-only preview with a fake `ProbeRunner` and `FormMemory.NONE` (existing constructor seams: `M/presentation/swing/AvailabilityPanel.java:149–164`), or the R0 in-memory Preferences factory. Confine chooser fixtures, logs, user-home overrides, and temporary files to `target/`. Do not launch the unconfined production main or browse external files. If a real display/scaling configuration is unavailable, record that manual check as unverified.

## 3. R1 — visible setup guidance and consistent terms

**Change:** clarify the current model, not its routing. Use “Device identifier” consistently for multi-family inputs/table headers; explain DID where applicable. Rename “Known lists” to “Server profile” and clarify that “Transport” selects a device protocol/family. Replace the engineering subtitle with a task/evidence explanation. Put CSV/TXT and tagged-family examples **beside batch opening before a file is selected**. Explicitly say “Opens and starts testing; batch routes each identifier using known profiles; the single-check server list is not used.” Describe timeout as a per-device budget and account-file selection as **not yet validated**, not a camera password.

**Why:** addresses UX02/03 and the help portion of UX11/12 with no request/backend changes. Current mismatches: `M/presentation/swing/AvailabilityPanel.java:120,240–246,325,332–339,529–560`; `M/presentation/swing/BatchResultsTableModel.java:15–17`; account loading occurs later (`M/infrastructure/configuration/FileYooseeCredentials.java:26–63`).

**Files:** `M/presentation/swing/AvailabilityPanel.java`, `M/presentation/swing/BatchResultsTableModel.java`, and display-only wording in `M/presentation/AvailabilityPresenter.java` where necessary; corresponding panel/presenter tests. Preserve component names and status meaning. Do not silently redefine CSV header `Serial` when changing UI terminology.

**Risk:** low; header-based table lookups/tests need synchronized display-name updates. This item does not invent an account-provisioning flow or claim that an environment file has been validated; authoritative account configuration status is deferred pending that contract.

**Verify:** characterization requests/results unchanged; format hint visible before import; button/menu both warn about immediate testing; mixed-family file still routes identically. Manually ask a user which settings apply to a single check versus a batch before they run either.

## 4. R2 — result ownership and immediate cancellation acknowledgment

**Change:** add a prominent result-context caption: single identifier, batch summary, or selected batch row/source line/description. Keep the single form unchanged when inspecting rows. On Cancel, immediately acknowledge “Cancellation requested — waiting for cleanup,” while maintaining locked inputs and existing token semantics. Prevent later progress callbacks from masking that acknowledgment until completion; final statuses/evidence remain authoritative.

**Why:** UX09/10. Current identity context is buried in detailed evidence and Cancel only sets a token. `M/presentation/AvailabilityPresenter.java:116–126`; `M/presentation/swing/AvailabilityPanel.java:302–310,569–600,649–690`.

**Files:** `M/presentation/swing/AvailabilityPanel.java`; `M/presentation/AvailabilityPresenter.java` only for display/context state if needed; existing single/batch/parallel panel tests plus R0 context tests.

**Risk:** low, provided no early unlock, worker interruption, or status reclassification occurs. Do not report cancellation as complete before cleanup.

**Verify:** simulated slow single and parallel batch checks; Cancel immediately changes acknowledgment; no further devices start after cancellation is observed; controls stay locked through cleanup; retained evidence/untested rows stay distinct. Sort/select repeated-ID rows and ensure prominent context matches the displayed metadata, not the current form.

## 5. R3 — keyboard access to current commands

**Change:** add nonconflicting field/button mnemonics, deliberate initial focus, and an application cancel key binding active only during work. Keep platform Ctrl/Cmd+O, default Check behavior, and Enter-in-HiChip-serial behavior. Verify ordinary tab traversal in visual order; revisit it after R4 rather than adding a heavyweight traversal policy unnecessarily. Preserve text-area newline and normal copy/navigation keys.

**Why:** UX05. Associations/default actions exist, but application cancel/field mnemonics do not. `M/presentation/swing/AvailabilityPanel.java:183–189,248–256,292–318,714–718`.

**Files:** `M/presentation/swing/AvailabilityPanel.java`, `T/presentation/swing/AvailabilityPanelTest.java`, `T/presentation/swing/BatchAvailabilityPanelTest.java`; bootstrap test only if initial focus is set at window level.

**Risk:** low; do not shadow chooser cancel, combo-popup Escape, table navigation, or HiChip serial Enter.

**Verify:** headless tests invoke mapped actions on the EDT and inspect mnemonic/label relationships; actual keyboard-only desktop smoke test covers focus, Enter, open-file shortcut, cancellation, and modal dialogs. The current default-button test only checks wiring, not physical key handling (`T/presentation/swing/AvailabilityPanelTest.java:609–617`).

## 6. R4 — progressive disclosure and a usable small-window layout

**Change:** keep identifier, profile/effective route summary, Check, and result headline together. Move raw server editing/timeout/protocol overrides, HiChip construction, detailed evidence, and diagnostics into clearly labeled expandable sections using existing Swing panels/layouts. Place batch concurrency with batch commands. Keep effective values readable when sections are collapsed; hidden fields still contribute exactly the same request. Adapt to stacked cards at a narrow width and cap initial packed size to available screen bounds, retaining scrolling for exceptional content.

Account selection must remain discoverable for **mixed-family batches even when the single-check protocol is PPCS/XQ**. Do not remove it based solely on the single-check combo. Reuse existing component instances/names/listeners; extract small view-building sections rather than rewriting worker/business code.

**Why:** UX01/06/13. The form is already layout-managed; replacing nonexistent absolute layouts or introducing a wizard would add risk without solving its density. `M/presentation/swing/AvailabilityPanel.java:330–388,693–760`; `M/bootstrap/AvailabilityApplication.java:71–84`.

**Files:** `M/presentation/swing/AvailabilityPanel.java`, `M/bootstrap/AvailabilityApplication.java`; `M/presentation/swing/AvailabilityTheme.java` only for shared spacing/relative font sizing if needed; panel/bootstrap/theme tests, and small proposed layout tests under `T/presentation/swing/`.

**Risk:** medium for geometry, focus, and action discoverability. No automatic profile matching, clearing hidden values, LAN disabling, or backend-specific default changes.

**Verify:** compare captured single/batch requests before/after collapse/expand and resize; all controls remain keyboard-reachable; section toggling never runs a probe. Manual checks at 720×560, a typical laptop size, and available 100/150/200% scaling configurations: primary task usable, result ownership visible, no inaccessible clipped controls, both batch/account paths discoverable. Record actual bounds/screenshots after approval, not guessed pixel targets now.

## 7. R5 — field-local validation and safe next steps

**Change:** retain validation on submission, but also mark/focus the offending input and show its existing message beside it. Reuse presenter/domain validation instead of duplicating regex/business rules in listeners. If structured field attribution is needed, extract a small presenter result/helper with unit tests first. Keep the result card clear of stale endpoints. Add safe recovery guidance for existing failure categories without exposing credential fields/content or relabeling unknown as offline.

**Why:** UX04 and remaining UX12. Current request parses server configuration **before** identifier validation; exceptions become general result-card messages. `M/presentation/AvailabilityPresenter.java:52–60,146–152`; `M/presentation/swing/AvailabilityPanel.java:444–463,649–660`.

**Files:** `M/presentation/AvailabilityPresenter.java`, `M/presentation/swing/AvailabilityPanel.java`; presenter and panel tests. No protocol/credential-parser changes in this item.

**Risk:** medium. Preserve validation precedence and messages for all-invalid input; encoded transport must still determine effective identifier validation. Do not disable Check based on a new divergent validator or validate account files on the EDT. If richer remote diagnosis requires changing backend errors/redaction, stop for separate approval.

**Verify:** invalid identifier, invalid hosts/ports, both invalid, encoded overrides, blank legacy servers with LAN off/on, HiChip invalid serial, GWell leading zero, case-sensitive IoTVideo. No runner call for rejected input; focus/error marker matches the actual failure; corrected fields clear their marker without starting work.

## 8. R6 — searchable batch inspection and numeric duration ordering

**Change:** add display-only identifier/description search and outcome filters with visible “shown / total” counts. Keep the same model data, source-line identity, full twelve-column access, and global batch summary. Add an elapsed-duration comparator/sort key while retaining displayed `"… ms"` values and defined placement for queued/absent values. Use the existing JTable sorter plus JDK `TableRowSorter`/`RowFilter`; no new dependency.

**Why:** UX07/08. Sorting exists already; filtering and correct duration ordering are the missing features. `M/presentation/swing/AvailabilityPanel.java:257–269,306–310`; `M/presentation/swing/BatchResultsTableModel.java:59–82`.

**Files:** `M/presentation/swing/AvailabilityPanel.java`, `M/presentation/swing/BatchResultsTableModel.java`; proposed direct model/sort/filter tests and existing batch/P2P panel tests.

**Risk:** medium: always convert view row to model row; filtered-out selection must not display another device. Search must not trigger probes, change routes, discard rows, or change imported order. Treat search as literal text unless a separately agreed regex feature is needed. Preserve cancellation and summary counts independently of filtering.

**Verify:** `2 ms`, `10 ms`, `100 ms`, and missing durations; repeated IDs with different descriptions; non-ASCII/quoted descriptions; outcome filters and zero matches; completion while a filter is active; sorted+filtered selection displays correct evidence. A representative large synthetic list is easy to locate by identifier or description, and clearing filters restores every row without another network call.

## 9. R7 — conditional EDT throughput improvements

**Change:** first measure EDT responsiveness with representative synthetic batches and existing fakes. If update bursts cause visible lag, coalesce table/progress/log delivery or extract a small ordered UI-event helper, leaving batch scheduling/protocol services untouched. Add an auto-follow log toggle if sustained log inspection is a confirmed need. Maintain the existing 2,000-line retention rule and complete result data.

**Why:** background network/file work is already implemented correctly, but each event still performs EDT document/table work. `M/presentation/swing/AvailabilityPanel.java:569–600,663–671`; `M/presentation/swing/BatchResultsTableModel.java:32–35`.

**Files:** `M/presentation/swing/AvailabilityPanel.java`, possibly `M/presentation/swing/BatchResultsTableModel.java`; new helper only if justified, under the existing presentation package; batch/parallel panel tests.

**Risk:** medium for ordering. No dropped result evidence or premature final summary/unlock; all queued UI updates must be settled consistently before completion is presented. No replacement of account/listener ownership locks or concurrency defaults.

**Verify:** capture before/after EDT latency for the agreed batch size; all events mutate Swing on the EDT; rows/progress/final summary match baseline; diagnostics retain the same bounded tail; cancellation remains responsive and cleanup-safe. Skip the optimization if measurements show no meaningful lag.

## 10. R8 — conditional Preferences latency removal

**Outcome 2026-10-09:** confined measurements and contract tests are complete; the user chose **Keep current timing** and declined native-store profiling. Preserve synchronous load/save/flush and save-before-probe behavior. Do not implement the proposed adapter under the current approval. See [R8 results](03-implementation.md#r8--confined-preferences-measurements-and-contract-characterization).

**Change:** measure form load/save/flush latency. If it blocks interaction materially, propose a serialized off-EDT persistence adapter using the existing `FormMemory` seam, with snapshot/order/shutdown tests. **Stop and obtain approval for changed persistence timing before implementing**: current save finishes before a probe starts, and moving it can change last-write/shutdown behavior.

**Why:** this is the one clearly identified synchronous persistence path, not a missing network worker. `M/presentation/swing/AvailabilityPanel.java:177,465–474`; `M/presentation/swing/PreferencesFormMemory.java:45–57`.

**Files:** `M/presentation/swing/PreferencesFormMemory.java`, `M/presentation/swing/FormMemory.java`, `M/presentation/swing/AvailabilityPanel.java`, `M/bootstrap/AvailabilityApplication.java` only as needed for lifecycle ownership; memory/bootstrap/panel tests using the R0 in-memory store.

**Risk:** medium and behavior-adjacent. Never save account data, save unsent form edits as if submitted, reorder snapshots, lose the agreed final save, or turn convenience persistence failures into failed probes. If the existing timing is contractual, retain it and do not implement this item.

**Verify:** fake slow/failing store, successive submissions, closure during persistence, corrupted saved values, and correct restoration. Probe requests/results must remain identical; EDT stays responsive under slow-store simulation; agreed persistence order is proven.

## 11. Execution after approval

1. Create a **separate branch in this same repository**, without a worktree outside it. Keep unrelated/user changes intact.
2. Implement **one approved roadmap item per commit** with a clear item-specific message. R0 precedes production refactoring. Do not bundle backend/protocol cleanup with UX work.
3. Before extracting logic, add the relevant characterization/unit test; keep Swing updates on the EDT and long operations off it. Preserve the current MVP/layer boundaries rather than introducing a replacement framework.
4. After each item: compile, run full tests, report the actual outcome, changed files, manual test path, and what users notice. If build or validation is blocked, say so and do not claim the item verified.
5. Stop for any newly discovered behavior change: automatic profile selection, new import preview, altered defaults/timeouts/ownership, result reclassification, credential handling, or persistence timing.

**Not in this rescue without further evidence/approval:** a new UI toolkit, another look-and-feel/library, protocol rewrites, standard-MD5 substitution, backend error disclosure, export/history/printing, or a multi-step wizard. These are either unnecessary for the observed one-screen structure or new/compatibility-sensitive scope.

**Approved scope:** the ordered Swing-only roadmap above. R0's safety gate is green; R7's measurements justified the bounded presentation-only update delivery now implemented. R8's decision is to retain synchronous timing and skip the conditional optimization. Reopening it requires new evidence and explicit approval for any changed persistence or repository boundary.
