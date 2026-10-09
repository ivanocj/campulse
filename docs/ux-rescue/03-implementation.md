# UX rescue — implementation results

R0–R6 branch: **`ux-rescue/r0-safety-net`**, now merged. One roadmap item per commit. R0 left application code unchanged; R1–R6 change presentation guidance/state/access/layout/inspection only, not backend business rules. R7 is separate work on **`ux-rescue/r7-edt-responsiveness`**; backend business rules and persistence timing remain unchanged.

## R0 — test safety net

**Completed 2026-10-08.** Maven Central downloads into the repository-contained cache were explicitly approved. External Maven settings/caches were not inspected or reused.

### Changes

- `pom.xml`: forked test JVM uses repository-local home/temp paths and a test-only in-memory Preferences factory, set at JVM startup. Mockito agent and headless Swing configuration are retained.
- `src/test/java/com/ivanocj/cameras/availability/testsupport/InMemoryPreferencesFactory.java`: implements the JDK Preferences SPI entirely in memory; user/system roots are separate and node removal/recreation respects the API.
- `src/test/java/com/ivanocj/cameras/availability/testsupport/InMemoryPreferencesFactoryTest.java`: four tests cover the active factory, local file paths, isolated roots, values/flush/sync, and removal/recreation.
- `src/test/java/com/ivanocj/cameras/availability/integration/logging/LoggingProcessTest.java`: child JVM home/temp paths stay in its test directory; Preferences remains in memory. Explicit scenario properties still override defaults. Selects the current JVM's platform executable without probing external files.
- `src/test/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityWorkflowCharacterizationTest.java`: five cases cover all four protocol families' single/custom versus batch/catalog requests, meaningful zero padding and case, skipped unknown routes, repeated-ID sorted inspection, busy selection, and a subsequent single check without stale row evidence.
- `src/test/java/com/ivanocj/cameras/availability/presentation/swing/BatchResultsTableModelTest.java`: five tests cover all twelve columns, types/read-only behavior, queued/skipped rows, independent repeated identifiers, direct/relay/cancelled evidence, absent endpoints, and replacement imports.

**No files under `src/main/` were changed.** No defaults, network/protocol logic, persistence timing, credentials, labels, or screens were changed. The original suite ran successfully before the new workflow/table tests were added.

### Verification

Java / javac: **21.0.12.1**. Maven: **3.10.0**.

| Check | Result | Repository-local evidence |
|---|---|---|
| Production compile | PASS; all 73 production files compiled for Java 21 | `target/ux-rescue-build/baseline-compile.log` |
| Preferences confinement tests | PASS; 4 cases | `target/ux-rescue-build/preferences-isolation.log` |
| Existing suite + confinement tests | PASS; 472 cases, zero failures/errors/skips | `target/ux-rescue-build/baseline-tests.log` |
| New workflow/table characterization | PASS; 10 cases | `target/ux-rescue-build/r0-characterization-tests.log` |
| Full suite + executable JAR package | PASS; **482 cases**, zero failures/errors/skips | `target/ux-rescue-build/r0-package.log`; `target/surefire-reports/` |
| Diff whitespace check | PASS | `git diff --check` |

Packaging reports module-descriptor and duplicate manifest/license/notice warnings from the existing Shade configuration. They were not hidden or addressed in this test-only item. The packaged desktop and optional installer were not launched.

### Reproduce from the repository root

Create the build-only directories if needed:

```sh
mkdir -p target/ux-rescue-build/home target/ux-rescue-build/tmp
```

Use `target/ux-rescue-build/settings.xml` as both user/global Maven settings. If absent, create this **inside the repository**:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>
```

With the now-populated cache, run the complete verification offline:

```sh
HOME="$PWD/target/ux-rescue-build/home" \
TMPDIR="$PWD/target/ux-rescue-build/tmp" \
MAVEN_OPTS="-Duser.home=$PWD/target/ux-rescue-build/home -Djava.io.tmpdir=$PWD/target/ux-rescue-build/tmp -Djansi.tmpdir=$PWD/target/ux-rescue-build/tmp" \
mvn --offline --batch-mode --no-transfer-progress \
  --settings target/ux-rescue-build/settings.xml \
  --global-settings target/ux-rescue-build/settings.xml \
  -Dmaven.repo.local="$PWD/target/ux-rescue-build/repository" package
```

Use `test -Dtest=InMemoryPreferencesFactoryTest,AvailabilityWorkflowCharacterizationTest,BatchResultsTableModelTest` instead of `package` for the 14 new cases only. An empty cache requires the approved dependency downloads first; do not fall back to external caches/settings.

### Manual testing and user-visible effect

There is **no UI change to exercise manually** in R0. Run the targeted/full commands above to verify the safety net; all Swing-driven characterization runs on the EDT with fake probes, not production cameras/accounts. Real screen geometry/keyboard/HiDPI testing remains for the later UI items using the confined preview described in the roadmap.

Users will notice no change yet. The benefit is a verified, repository-confined baseline protecting business behavior before R1's visible setup-guidance improvements.

## R1 — clear device and batch setup

**Completed 2026-10-08.** Presentation changes only; no backend/domain/default/persistence changes.

### What users notice

- Identifier examples change with the selected protocol: composite DID, leading-zero GWell ID, or case-sensitive IoTVideo key. Matching tooltip/accessibility descriptions update without modifying entered values.
- “Server profile / Servers · single check” makes scope explicit; “Protocol / device family” and per-device timeout labels replace engineering shorthand.
- Batch formats, tagged-family examples, and catalog-routing behavior are visible **before opening a file**, not inside the initially hidden results card.
- Menu and button both say **Open batch file & test…** and explain that work starts immediately.
- Account guidance distinguishes selecting a file from validating credentials and says these are account credentials, not a camera password. No credential path or contents are displayed.
- Batch headers use **Device identifier** and **Protocol**; summaries count **entries**, including repeated IDs, without changing checked/reachable/failed/skipped/cancelled calculations or status meaning.

### Files and verification

Production files: `src/main/java/com/ivanocj/cameras/availability/presentation/AvailabilityPresenter.java`, `src/main/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityPanel.java`, and `src/main/java/com/ivanocj/cameras/availability/presentation/swing/BatchResultsTableModel.java`.

Updated the corresponding presenter/panel/table/workflow tests and added three tests for protocol-specific guidance, unchanged inputs/defaults, visible batch help, and non-focusable help text. Added test-only preview fixtures at `src/test/java/com/ivanocj/cameras/availability/bootstrap/AvailabilityPreview.java` and `src/test/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityPreviewFactory.java`.

- Targeted presentation/workflow suite: **80 cases passed** (`target/ux-rescue-build/r1-targeted-tests.log`).
- Final Java 21 compile/full suite/package: **485 cases passed**, zero failures/errors/skips (`target/ux-rescue-build/r1-package.log`).
- Initial offscreen Swing render inspected: `target/ux-rescue-build/preview/availability.png`. The new guidance is readable and fully rendered at natural size. This is **not** real-display/HiDPI/keyboard verification; the existing tall two-column layout is unchanged and remains R4's responsibility.
- No libraries added. Request construction, catalog selection, timeouts, cancellation, credential loading, and endpoint/reachability semantics are unchanged. R0's characterization cases remain green.

### Manual preview

After the confined `package` command above, run **from the repository root**:

```sh
HOME="$PWD/target/ux-rescue-build/home" \
TMPDIR="$PWD/target/ux-rescue-build/tmp" \
java -Duser.home="$PWD/target" -Djava.io.tmpdir="$PWD/target" \
  -Djava.util.prefs.PreferencesFactory=com.ivanocj.cameras.availability.testsupport.InMemoryPreferencesFactory \
  -Djava.awt.headless=false \
  -cp "target/test-classes:target/classes:target/campulse-1.0.0.jar" \
  com.ivanocj.cameras.availability.bootstrap.AvailabilityPreview
```

This uses the actual production window/panel with fake probes and `FormMemory.NONE`. Batch opening selects the repository's fabricated test-only fixture automatically; account selection is cancelled without opening a filesystem browser. It performs no network checks or real credential reads and does not write platform Preferences. These fixtures are test-only, not part of the application JAR.

1. Before any operation, locate the CSV/TXT, tagged-ID, and batch-routing guidance.
2. Change protocol through PPCS/XQ/GWell/IoTVideo: examples update; typed identifier, server text, timeout, and LAN setting stay unchanged.
3. Inspect File menu/button wording and tooltips; both announce immediate testing. Open the sample batch and check the generic identifier/protocol column headings.
4. Confirm account help distinguishes selection from validation. Tab navigation must skip the new help text.

For headless visual inspection, use `-Djava.awt.headless=true` and add `--snapshot`; the PNG is written only to `target/ux-rescue-build/preview/availability.png`. Do not substitute the production main for this confined preview.

## R2 — result ownership and cancellation acknowledgment

**Completed 2026-10-08.** Only presentation code and test/preview fixtures changed. No changes to probe services, routing, defaults, credentials, persistence, or worker cleanup.

### What users notice

- A bold, selectable caption above the outcome identifies the **submitted single-check protocol/identifier**, **batch summary/file**, or **selected batch source line/identifier/description**. Long or multiline context wraps and can scroll without discarding data.
- Editing the form does not relabel an earlier result. Inspecting a sorted/repeated-ID batch row still leaves the single-check form unchanged. Invalid input identifies the unsubmitted form or HiChip builder rather than retaining an unrelated row caption.
- Cancel immediately displays **Cancellation requested** and explains that inputs stay locked while the operation/resource cleanup finishes. Batch counts, rows, and logs continue to update without overwriting this acknowledgment.
- Final outcomes remain authoritative: requesting cancellation does not force an already-completed success/failure into a cancelled status. Cancel/close retain their existing token/cleanup behavior; controls unlock only at the existing completion point.

### Files and verification

- `src/main/java/com/ivanocj/cameras/availability/presentation/AvailabilityPresenter.java`: toolkit-independent context formatting and a progress-only cancellation acknowledgment.
- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityPanel.java`: read-only result-owner caption, submitted/source-row context updates, immediate Cancel acknowledgment, and a guard against progress masking it. No worker interruption or backend changes.
- Tests: extended `AvailabilityWorkflowCharacterizationTest`, `AvailabilityPresenterTest`, `AvailabilityPanelTest`, and `BatchAvailabilityPanelTest`; added `CancellationFeedbackTest`. Coverage includes all protocol identities, sorted repeated IDs, skipped rows, stale-context clearing, delayed cleanup, sequential/parallel cancellation, queued import progress, partial endpoint evidence, late cancellation, and fresh tokens on subsequent checks.
- Test-only preview: fake checks last up to two seconds and simulate a one-second cancellation cleanup interval; no real network/resources or private account files are used. `AvailabilityPreview --snapshot cancel` drives the actual Check/Cancel actions for visual inspection.

Verification evidence under `target/ux-rescue-build/`:

| Check | Result | Evidence |
|---|---|---|
| Before implementation | Nine regression failures confirmed missing caption/acknowledgment; compilation succeeded | `r2-red-tests.log` |
| Targeted presenter/UI/workflow/cancellation suite | **87 cases passed** | `r2-targeted-tests.log` |
| Java 21 compile/full suite/package | **495 cases passed**, zero failures/errors/skips | `r2-package.log`; `target/surefire-reports/` |
| Initial and cancellation-requested offscreen renders | Inspected; owner, acknowledgment, cleanup guidance, and locked inputs are visible | `preview/availability.png`; `preview/availability-cancelling.png` |
| Diff whitespace check | PASS | `git diff --check` |

Real-display keyboard/HiDPI checks remain unverified. The tall two-column layout remains R4's responsibility. Existing Shade warnings are unchanged.

### Manual check

Use the confined [desktop-preview command](#manual-preview) above, not the production main:

1. Enter a valid example identifier (e.g. `FFFF-1-A`) and Check. The caption must identify the submitted protocol/identifier. Changing form text after completion must not change that caption.
2. Open the synthetic fixture batch. After completion, sort/select a row: the caption must show its file, physical source line, identifier, and description; the single-check form must remain untouched.
3. Start another single or batch check, then Cancel during the simulated work. **Cancellation requested** should appear immediately and remain through cleanup, with inputs locked. The terminal status and unlocked controls appear only after cleanup returns.
4. Start another check to confirm the acknowledgment is replaced by normal checking and the new submitted owner.

For the cancellation-state PNG, use `-Djava.awt.headless=true` with `AvailabilityPreview --snapshot cancel`. Both preview outputs remain under `target/ux-rescue-build/preview/`.

## R3 — keyboard access to existing commands

**Completed 2026-10-08.** No new business commands, backend changes, dependencies, or custom focus-traversal policy.

### What users notice

- Initial focus is requested in the device-identifier field when the window first opens. Returning to the window or finishing a check does not reset focus.
- **Ctrl/Cmd+.** requests cancellation using the same enabled action as the Cancel button. Both remain inactive while idle and retain the existing token, acknowledgment, and cleanup locking.
- Configuration labels and buttons have nonconflicting mnemonic letters. The look-and-feel/platform determines modifier keys and when underlines are shown.
- Existing **Ctrl/Cmd+O**, default-button Enter, Enter-in-HiChip-serial, native Escape, text editing/copy keys, and table navigation are retained. Multiline text areas retain standard Swing editing: use **Ctrl+Tab / Ctrl+Shift+Tab** to move out of them rather than inserting a tab.

| Configuration field | Mnemonic letter | Command | Mnemonic letter |
|---|---|---|---|
| Device identifier | D | Check availability | C |
| Server profile | P | Open batch file & test | O |
| Servers | S | Cancel | A |
| Timeout | T | Select account file | E |
| Protocol / device family | R | Clear account file | U |
| Parallel batch checks | K | Build HiChip DID | B |
| HiChip prefix | H | Clear diagnostics | L |
| HiChip serial | I | Search local network | N |

File menu retains **F**. The batch menu item shares the existing batch action; its mnemonic is menu-scoped. No Escape binding was added, and the cancellation shortcut is scoped to the focused application window, not globally registered.

### Files and verification

- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityPanel.java`: shared Cancel action, window-scoped shortcut, mnemonics/label associations, and startup focus request.
- `src/main/java/com/ivanocj/cameras/availability/bootstrap/AvailabilityApplication.java`: requests identifier focus on `windowOpened` only; close behavior is unchanged.
- `KeyboardNavigationTest`: mnemonic uniqueness/visible-letter indices, label targets, idle shortcut safety, actual root Enter action, HiChip Enter, existing editing/Escape/table bindings, batch shortcut sharing, and configuration hierarchy order.
- `CancellationFeedbackTest`: now exercises both button and keyboard cancellation for single, sequential, and parallel jobs, including cleanup locking and disabled actions afterward.
- `AvailabilityApplicationTest`: verifies startup-only focus wiring, without activation-time focus stealing.
- New test-only `src/test/java/com/ivanocj/cameras/availability/bootstrap/KeyboardPreviewSmoke.java`: real-window initial focus/traversal and synthetic JVM key dispatch, restricted to its active preview window. No OS-wide Robot input, network, private account files, or platform Preferences.

| Check | Result | Repository-local evidence |
|---|---|---|
| Pre-implementation keyboard characterization | Six cases compiled; four existing behaviors passed; two failures confirmed missing mnemonics/cancel binding | `target/ux-rescue-build/r3-red-tests.log` |
| Targeted shortcut/cancellation/bootstrap tests | 18 cases passed before the hierarchy audit was added | `target/ux-rescue-build/r3-targeted-tests.log` |
| Java 21 compile/full suite/package | **506 cases passed**, zero failures/errors/skips | `target/ux-rescue-build/r3-package.log`; `target/surefire-reports/` |
| Real-window smoke | PASS: initial focus, existing visual traversal order, Enter submission, platform cancellation shortcut, and cleanup completion | `target/ux-rescue-build/r3-live-keyboard.log` |
| Diff whitespace check | PASS | `git diff --check` |

The real-window smoke used synthetic events inside that JVM, not physical OS key presses. OS-specific mnemonic modifiers, native chooser/menu interactions, and multiple HiDPI scales still require manual checks. A bare headless root cannot validate actual display focus; the headless test audits hierarchy/bindings, while the separate real-window smoke checks focus traversal. No focus-policy override was needed.

### Keyboard-only manual check

Run the confined [desktop preview](#manual-preview) from the repository root:

1. Confirm the identifier initially has focus. Enter a valid example (`FFFF-1-A`) and press Enter; a fake check starts.
2. During work, press **Ctrl/Cmd+.**. Confirm immediate acknowledgment and locked inputs until simulated cleanup finishes. Pressing the shortcut while idle must do nothing.
3. Move through controls with Tab/Shift+Tab; use Ctrl+Tab/Ctrl+Shift+Tab in multiline areas. Disabled Cancel and non-focusable help are skipped; result context remains reachable/selectable.
4. Use the platform mnemonic modifiers to focus each configuration field and invoke existing commands. Verify the mnemonic still works after the account-button caption changes in ordinary application testing; the confined preview deliberately cancels account selection.
5. Enter a valid HiChip serial and press Enter: it builds a DID but does not start a check. In this fake-runner preview, Ctrl/Cmd+O opens/tests the synthetic fixture.
6. Open a protocol/profile popup and press Escape: it must close the popup, not cancel a job. Verify copy/arrows/newline behavior in text/table controls. Native chooser Escape must be checked separately with repository-local fixtures; the preview intentionally avoids filesystem browsers.

For repeatable real-window focus/key-dispatch verification, use the preview command with the final class changed to `com.ivanocj.cameras.availability.bootstrap.KeyboardPreviewSmoke`. It closes its own window after the check and refuses key dispatch unless that preview owns focus.

## R4 — compact tasks, progressive disclosure, and small-display layout

**Completed 2026-10-08.** Swing/JDK layout changes only; no dependencies or backend/domain/configuration/persistence changes.

### What users notice

- The primary card keeps identifier, profile, selected settings, Check, and Cancel together. The result card keeps ownership, outcome, health, elapsed time, and device address visible without the full technical report.
- Connection overrides, detailed evidence, HiChip tools, diagnostics, and Yoosee account controls start collapsed and open with ordinary keyboard-accessible toggle buttons. Expanding/collapsing only changes visibility: the same components, values, listeners, and busy-enabled states remain in use.
- Selected protocol, timeout, LAN setting, and complete raw server text remain in a selectable, wrapping/scrollable summary. It is explicitly selected input, not prevalidated configuration; encoded configuration still resolves its protocol through the original submission path.
- Batch opening and concurrency are grouped together. **Yoosee account · single & batch** is always discoverable, including when the single-check protocol is PPCS/XQ. Batch format/routing help remains visible before import.
- Cards stack on a narrow viewport and sit side-by-side when wide. The main form tracks viewport width; exceptional/expanded content scrolls vertically rather than forcing a horizontal form scrollbar. Focus-driven scrolling reveals controls reached by keyboard.
- Startup size is capped to the monitor's usable logical bounds (including taskbar/menu insets and negative monitor origins); on a smaller display the minimum is clamped to that display rather than exceeding it.

### Files and design decisions

- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityPanel.java`: smaller task cards, shared batch/account grouping, selected-settings summary, Scrollable width tracking, disclosures, and content-sized BoxLayout rows. Component names/instances and workflow methods are preserved; resize invalidation updates width-dependent heights.
- New UI helpers in `src/main/java/com/ivanocj/cameras/availability/presentation/swing/`: `CollapsibleSection.java` (visibility/accessibility/focus return), `ResponsiveCards.java` (small JDK LayoutManager for two content-sized cards without reparenting), and `KeyboardScrollSupport.java` (outer-form focus scrolling, including text fields with their own horizontal-scroll behavior).
- `src/main/java/com/ivanocj/cameras/availability/bootstrap/AvailabilityApplication.java` and new `WindowBounds.java`: screen-aware startup sizing; pure sizing arithmetic is unit-tested. Window close/cancellation behavior is unchanged.
- Retained FlatLaf and JDK components. No null layouts were introduced. The only new card geometry is inside its layout manager, derived from available width/content height; no input fields have hard-coded positions.
- Added layout/disclosure/window-bound tests and extended existing request characterization, keyboard hierarchy, and native preview fixtures. Text-pane preferred heights are retained even in narrower expanded cards, avoiding the previous GridBag minimum-height fallback.

### Verification

| Check | Result | Repository-local evidence |
|---|---|---|
| Pre-implementation layout checks | Four failures confirmed missing disclosures/viewport behavior | `target/ux-rescue-build/r4-red-tests.log` |
| Java 21 compile/full suite/package | **518 cases passed**, zero failures/errors/skips | `target/ux-rescue-build/r4-package.log`; `target/surefire-reports/` |
| Request preservation | All four-family single/batch characterization cases pass after disclosure toggling; customized collapsed requests retain identity, servers, timeout, protocol, and LAN option | Existing characterization tests + `AvailabilityLayoutTest` |
| Layout/disclosure safety | Defaults collapsed; same components/state retained; no layout-triggered probes; small/wide/small reflow; no horizontal form overflow; selected-settings summary; expanded height and keyboard scrolling | `AvailabilityLayoutTest`; `CollapsibleSectionTest` |
| Bounds arithmetic | PASS: taskbar insets, negative-origin monitor, normal minimum, and display smaller than minimum | `WindowBoundsTest` |
| Offscreen renders | Inspected at **720×560**, **1100×700**, and expanded **960×900**; compact primary Check/result headline and expanded text panes render correctly | `target/ux-rescue-build/preview/availability-small.png`, `availability-wide.png`, `availability-expanded.png` |
| Native layout on available display | PASS: startup **970×656** inside usable **1710×1072**, **2× native display transform**, collapsed/expanded traversal policies, narrow stacking, and expanded controls scroll fully into view | `target/ux-rescue-build/r4-live-layout.log` |
| Diff whitespace | PASS | `git diff --check` |

**Verification limit:** the OS left the preview window inactive in this run (`active=false`, no focus owner), so the guarded live key-dispatch smoke could not acquire focus. No keys were dispatched elsewhere and no external OS settings/permissions were changed. Native layout/traversal-policy checks ran in `--layout-only` mode instead. Physical keyboard/focus behavior after folding and 100%/150% configurations still require manual checks; 2× native layout was available. Existing Shade warnings remain outside this item.

### Layout manual check

Use the confined [desktop-preview command](#manual-preview) from the repository root:

1. Resize to 720×560, a normal laptop window, and a wider window. Confirm Check and the initial result headline are nearby, cards stack/split appropriately, and no horizontal form scrolling is necessary.
2. Expand Connection settings; change a supported protocol, server list, timeout, and LAN option; collapse it. Read/scroll the selected-settings summary. Check and confirm the request uses those settings, not the disclosure state. Encoded-config overrides retain the original submission behavior.
3. Open HiChip tools, enter prefix/serial, and use Enter/Build. Open evidence/diagnostics to read/copy data. Close/reopen sections: values and current result ownership remain unchanged. Use Tab/Shift+Tab (Ctrl+Tab in multiline editors) and ensure offscreen focused controls scroll into view.
4. With single-check PPCS/XQ selected, expand **Yoosee account · single & batch**. Account controls must remain available for a mixed-family batch; disclosure state must not erase selection or change routing. The preview cancels account selection without browsing files.
5. Start a fake batch, toggle/read evidence and diagnostics during work, and cancel. Inputs stay locked through cleanup, summaries/evidence remain intact, and opening a section never starts another probe.

For native bounds/layout verification, run the preview command with final class `com.ivanocj.cameras.availability.bootstrap.KeyboardPreviewSmoke --layout-only`. Without that argument, it additionally requires active window focus and exercises guarded synthetic key dispatch.

For headless PNGs, use `-Djava.awt.headless=true` with `AvailabilityPreview --snapshot small`, `--snapshot wide`, or `--snapshot expanded`. Outputs stay under `target/ux-rescue-build/preview/`; these fixtures are not packaged into the application.

## R5 — field-local validation and safe recovery guidance

**Completed 2026-10-09.** Presentation-only changes; no protocol/domain/parser/credential/persistence changes or new dependencies.

### What users notice

- Rejected submissions display the **existing validation message beside the offending identifier, server list, or HiChip input**, with an error outline and textual “Error:” prefix. The input's accessible description includes the error; normal help/decoration is restored when the input is edited.
- A hidden invalid field automatically reveals its Connection settings or HiChip section, scrolls into view, and receives focus. Existing components, labels, mnemonics, and field values remain in use.
- Editing clears obsolete markers but does not run a probe, validate account files, declare input valid, or disable Check. Protocol/server edits also clear identifier errors whose effective protocol may have changed.
- The original result detail remains unchanged. A separate **Next step** hint supplies safe advice for local failures, batch-file errors, timeouts, server rejections, inconclusive outcomes, unsupported backends, and missing/rejected account credentials. It does not infer redacted causes or turn missing/unknown evidence into offline status. Fresh checking/cancellation/input states clear old advice.
- Invalid input still clears stale endpoint/evidence data. Cleanup locking, settings persistence timing, batch routing, and reachable counts are unchanged.

### Files and validation design

- `src/main/java/com/ivanocj/cameras/availability/presentation/AvailabilityPresenter.java`: `InvalidInput` remains an `IllegalArgumentException` with the original message/cause plus immutable field attribution. Server parsing still precedes identifier validation; encoded transport still determines the effective identifier/length rules. HiChip numeric parsing and generated-DID validation retain their order/messages; only after a generated-DID failure are the existing `DeviceId` rules reused to locate prefix/serial errors. Combined-length failures mark both inputs. `ResultView` carries separate recovery guidance, retaining its previous constructor forms and detail/evidence wording.
- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityPanel.java`: submission handlers apply the attributed messages, clear markers on relevant edits, reveal the affected section, and render recovery hints. Worker/network/credential handling is unchanged.
- New `src/main/java/com/ivanocj/cameras/availability/presentation/swing/FieldFeedback.java`: small EDT-only view wrapper for textual/accessible feedback, reversible FlatLaf outlines (including the server scroll-pane border), content-sized rows, and guarded deferred focus. Width-dependent height invalidation is coalesced onto the next EDT turn to avoid re-entering ancestor BoxLayout calculations.
- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/CollapsibleSection.java`: idempotent reveal operation, preserving normal toggle/accessibility behavior.
- New tests: `AvailabilityValidationTest`, `RecoveryGuidanceTest`, `FieldValidationPanelTest`, and `FieldFeedbackTest`; extended `CollapsibleSectionTest`. Coverage includes both-invalid precedence, hosts/ports, LAN-only blanks, encoded overrides/conflicts, GWell leading zeros, IoTVideo case, HiChip parsing/range/combined length, no rejected-input runner calls, marker clearing, original messages, nested-detail non-disclosure, stale evidence/advice clearing, and wrapped-message geometry.
- Test-only preview fixtures: extended `AvailabilityPreview`/`AvailabilityPreviewFactory` and added `ValidationPreviewSmoke`. The native smoke uses the real window and direct form actions, records unexpected asynchronous failures, and performs no OS-wide key input, network checks, or private credential reads.

### Verification

| Check | Result | Repository-local evidence |
|---|---|---|
| Pre-implementation characterization/regressions | Original validation-order/message cases: **9 passed**; **6 UI failures** confirmed missing local feedback/reveal | `target/ux-rescue-build/r5-red-tests.log` |
| Targeted presenter/UI/workflow/keyboard/cancellation suite | **87 passed**, zero failures/errors/skips | `target/ux-rescue-build/r5-targeted-tests.log` |
| Final Java 21 compile/full suite/package | **78 production and 83 test/support sources compiled; 549 cases passed**, zero failures/errors/skips | `target/ux-rescue-build/r5-package.log`; `target/surefire-reports/` |
| Offscreen error renders | Inspected identifier/server errors at **1100×820**, HiChip at **1100×900**; complete local messages and editable inputs render correctly | `target/ux-rescue-build/preview/availability-invalid-identifier.png`, `availability-invalid-servers.png`, `availability-invalid-hichip.png` |
| Native validation smoke | PASS at **720×560**: identifier, server list, and HiChip serial reveal/focus/scroll; full wrapped messages and unsqueezed controls; no asynchronous exceptions | `target/ux-rescue-build/r5-live-validation.log` |
| Diff whitespace | PASS | `git diff --check` |

Visual checking caught a wrapped HiChip error squeezing its input; geometry regression tests now protect that case. An intermediate synchronous layout-invalidation approach also produced native BoxLayout exceptions (`target/ux-rescue-build/r5-layout-regression.log`); it was replaced by deferred invalidation, and the final guarded native run is clean. Existing Shade warnings remain outside this item. Physical keyboard/screen-reader interactions and alternate display scales remain manual checks, not claims from these fixtures.

### Manual check

Use the confined [desktop-preview command](#manual-preview), not the production main:

1. Enter `broken` as the identifier and Check. Confirm the original message appears beside that field, it receives focus, and stale endpoints/evidence are absent. Correcting the text removes the marker without starting work.
2. Set the server list to `127.0.0.1:65536`, collapse Connection settings, and Check. The section should open and focus the server list. With both server/identifier invalid, the server message must appear first; fixing it then resubmitting reveals the identifier error.
3. Clear legacy servers with LAN off: Check rejects the server list. Enabling LAN clears its obsolete marker; submission still uses the existing LAN-only behavior. Encoded XQ/SY settings still select their own effective protocol.
4. Enter HiChip serial `abc`, or prefix `1` with a valid serial, and Build. The correct field receives the message/focus; a long wrapped message must not compress the input. Correcting/building a DID never starts a check.
5. Run a valid fake check and inspect its inconclusive outcome: original detail and separate safe Next step advice appear. Start another operation or submit invalid input: the previous advice disappears. No account path or contents should be revealed.

For repeatable native validation, use the preview command with final class `com.ivanocj.cameras.availability.bootstrap.ValidationPreviewSmoke`. `--layout-only` skips focus acquisition but still checks visibility, geometry, and scrollability. For error PNGs use `-Djava.awt.headless=true` and `AvailabilityPreview --snapshot invalid-identifier`, `--snapshot invalid-servers`, or `--snapshot invalid-hichip`; all outputs remain under `target/ux-rescue-build/preview/`.

## R6 — searchable batch inspection and numeric duration ordering

**Completed 2026-10-09.** Display-only Swing changes; no routing, protocol/domain, credential, default, persistence, or dependency changes.

### What users notice

- **Quick search** matches literal, case-insensitive text in the identifier or description. Punctuation, quotes, Unicode text, and repeated identifiers remain intact; searching does not normalize a stored identifier or change its routing.
- **Outcome** filters use the existing result captions, including queued/skipped/failed/cancelled rows and every discovery/presence/authentication distinction. Filters remain available during work, and matching rows enter/leave the view as results arrive.
- A separate **shown / total** label reflects visible rows; zero matches are explicit. The global batch summary still includes every imported entry. **Show all** clears both filters without another check, preserving the current sort order. Filters otherwise remain in effect, including when opening a replacement batch.
- **Elapsed** sorts by integer milliseconds rather than text: `2 ms`, `10 ms`, `100 ms`. Unmeasured queued/skipped/failed/untested durations follow measured values ascending and precede them descending. All twelve columns, their displayed values/types, table scrolling/tooltips, and source order in the model remain unchanged.
- Sorted/filtered selection always inspects the corresponding source row. If an inspected row is hidden or selection is cleared, the result card restores the full batch summary and clears row endpoints/evidence rather than displaying another device. Changing filters cannot relabel a later single-check result as an old batch selection.
- Search Enter is display-only, not the window's default Check action. New mnemonics are **Q** for Quick search, **M** for Outcome, and **W** for Show all; existing mnemonics and Check/open/cancel behavior are unchanged.

### Files and design

- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/BatchResultsInspection.java`: new small EDT-owned view helper using the JDK `TableRowSorter`/`RowFilter`. It derives outcome choices from existing presenter captions, uses literal substring matching, refilters completed rows, and reports counts without changing model data. Filter-triggered selection events are guarded until JTable settles its view/model mapping.
- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/BatchResultsTableModel.java`: named inspection-column indexes and a long-precision comparator for its existing elapsed strings. No column values, types, source-line keys, or result metadata changed.
- `src/main/java/com/ivanocj/cameras/availability/presentation/swing/AvailabilityPanel.java`: places filters above the table, retains the unfiltered terminal summary for safe empty-selection recovery, and preserves explicit view-to-model conversion and busy selection guards. Worker scheduling, cancellation tokens, progress/summary counts, and cleanup locking are unchanged.
- Tests: new `BatchInspectionPanelTest` and `BatchResultsInspectionTest`; extended `BatchResultsTableModelTest` and `KeyboardNavigationTest`. Existing batch/P2P/routing/parallel/cancellation characterization remains green.
- Test-only previews: new `src/test/java/com/ivanocj/cameras/availability/bootstrap/BatchInspectionPreview.java`; extended `AvailabilityPreviewFactory` and shared the existing offscreen snapshot helper. Synthetic mixed-family/repeated-ID CSV fixtures are created in separate directories under `target/ux-rescue-build/preview/`. Fake results include reference IPs only; no network, real account files, or platform Preferences are used. Production startup does not auto-import these fixtures, and they are not packaged in the application JAR.

### Verification

| Check | Result | Repository-local evidence |
|---|---|---|
| Pre-implementation regressions | **8 failures** confirmed missing filters and lexical/missing-first duration sorting; compilation succeeded | `target/ux-rescue-build/r6-red-tests.log` |
| Targeted model/filter/batch/P2P/workflow/keyboard/parallel/cancellation/layout suite | **66 passed**, zero failures/errors/skips | `target/ux-rescue-build/r6-targeted-tests.log` |
| Java 21 full suite and executable JAR package | **562 passed**, zero failures/errors/skips; package succeeded | `target/ux-rescue-build/r6-package.log`; `target/surefire-reports/` |
| Literal search and retained model data | Repeated IDs, quoted/multiline/Unicode descriptions, metacharacters, all outcome labels, zero matches, replacement imports, and original twelve values pass | `BatchInspectionPanelTest`; `BatchResultsTableModelTest` |
| Large synthetic list | Identifier/description lookup finds source row 4096 in **5,000 rows**; Show all restores every row without probes | `BatchInspectionPanelTest.aLargeSyntheticListCanBeFoundAndRestoredWithoutLosingRows` |
| Numeric sorting | `2/10/100 ms`, long-integer precision, equal durations, and missing values pass in the documented order | `BatchResultsTableModelTest`; `BatchInspectionPanelTest` |
| Ownership and ongoing work | Correct sorted/filtered repeated-ID evidence; hidden/empty selection clears evidence; later single ownership retained; live filter counts and cancellation remain cleanup-safe | `BatchInspectionPanelTest`; existing workflow/parallel/cancellation tests |
| Offscreen renders | Inspected unfiltered, filtered/selected, and zero-match views at **1100×1080**, plus stacked startup at **720×560**; headings, controls, row counts, and table column headers render correctly | `target/ux-rescue-build/preview/availability-batch-wide.png`, `availability-batch-filtered.png`, `availability-batch-empty.png`, `availability-batch-small.png` |
| Native small-window inspection | PASS at **720×560**: filter geometry/scrolling, headers, field actions, repeated-ID inspection, hidden-selection clearing, restored rows, and unchanged global summary; no asynchronous exceptions | `target/ux-rescue-build/r6-live-layout.log` |
| Diff whitespace | PASS | `git diff --check` |

**Keyboard verification limit:** the final guarded key-dispatch smoke could not acquire focus: the OS left the window inactive (`active=false`, no focus owner), although the search control was showing. No keys were dispatched elsewhere or external OS settings changed. Native inspection ran in layout-only mode instead; physical keys, screen readers, and alternate display scales still require manual checks. Failure evidence is retained in `target/ux-rescue-build/r6-live-batch-inspection.log`. This does not invalidate the headless action/selection tests or native geometry checks.

The final package command used the same confined offline setup as R0, with **`-Dmaven.jar.forceCreation=true`** so repeated builds shade a fresh project JAR rather than an already shaded artifact. No POM changes were needed; existing Shade module/manifest/license warnings remain outside this item. The 5,000-row test establishes correct inspection, not a measured EDT performance result; R7 remains conditional.

### Manual check

Use the confined [desktop-preview command](#manual-preview) with the final class changed to `com.ivanocj.cameras.availability.bootstrap.BatchInspectionPreview`. This test-only preview auto-imports seven synthetic entries with fast fake results, not the application's production network backends.

1. Search `north`: two separate repeated-ID rows appear, with **2 / 7 shown**. Search `[north]`: its punctuation is literal. Search absent text: **0 / 7 shown · No matches** appears; total batch counts do not change.
2. Select account-required or server-reported-online outcomes. Their original captions remain distinct from confirmed reachability. Show all restores seven rows without another check.
3. Scroll the table horizontally to Elapsed and click its header in both directions. Verify numeric millisecond ordering and the documented placement of `—`; sorting does not rearrange the model's source rows or remove any column.
4. With a sorted `north` view, inspect the Câmera row: the result owner identifies source line 3, its description, and its own evidence. Change search to `Garage`: the old selection disappears and the result card returns to the full batch summary with no stale endpoint. The single-check form remains untouched.
5. Press Enter in Quick search: it must not start a single check. Use the new mnemonics and Tab/Shift+Tab, resize to 720×560, and scroll to the batch card. Filters/counts remain accessible without horizontal main-form scrolling; the table retains its own horizontal scroll.

For repeatable native inspection use `BatchInspectionPreview --smoke` (requires active preview focus), or `--smoke --layout-only` (no key dispatch). For PNGs use `-Djava.awt.headless=true` with `--snapshot wide`, `--snapshot filtered`, `--snapshot empty`, or `--snapshot small`; outputs remain under `target/ux-rescue-build/preview/`.

**Next item at this checkpoint:** R7 — measure representative synthetic-batch EDT responsiveness first; optimize only if measurements justify it, as a separate commit. R8 additionally still requires a persistence-timing decision.

## R7 — measured, bounded EDT update delivery

**Completed 2026-10-09 on `ux-rescue/r7-edt-responsiveness`; not merged.** Native synthetic measurements demonstrated meaningful update-burst stalls before any production optimization. Only presentation code and test/measurement fixtures changed. R8 persistence timing is untouched.

### Measurement and decision

The new opt-in `src/test/java/com/ivanocj/cameras/availability/bootstrap/BatchResponsivenessPreview.java` drives the actual production window and batch action with `FormMemory.NONE`, fake probes, cancelled account selection, and repository-local generated CSV files. It measures enqueue-to-dispatch delay with a 10 ms heartbeat (at most one outstanding sample), plus maximum EDT event-dispatch duration and table/progress/document notification counts. Probe work remains off the EDT; the fixture checks row/source order, terminal completeness before unlocking, filtered row counts, all twelve columns, and the 2,000-line log bound.

Before/after: Java **21.0.12.1**, macOS native Swing (`headless=false`), default **8** parallel probes, **1,000 and 5,000 rows**, all four protocol families, repeated identifiers, unknown/skipped routes, quoted Unicode descriptions, expanded diagnostics, four fake diagnostic messages per probe, and zero probe delay to exercise rapid completion bursts. Each mode has a 100-row warm-up and three measured trials. Modes are unfiltered and active direct-P2P outcome filtering plus numeric elapsed sorting. The production baseline is commit `6179537`; the measurement fixtures were added before changing production delivery.

Repeated single-event/queue stalls of roughly a quarter second to over two seconds justified optimization. These are synthetic workload measurements, not live-camera throughput, measured usage, a hard latency guarantee, or timing assertions in CI. The heartbeat intentionally avoids flooding the queue; baseline p95 estimates have very few samples, so the observed maximum delay is the primary comparison.

| Rows | View | Before: per-trial maximum queue delay (ms) | After: per-trial maximum queue delay (ms) | Median whole operation before → after (ms) |
|---|---|---|---|---|
| 1,000 | All rows | 244.3–365.9 | 6.5–20.9 | 402.1 → 126.7 |
| 1,000 | Outcome + elapsed sort | 254.8–296.5 | 3.7–8.2 | 297.5 → 78.1 |
| 5,000 | All rows | 1,536.5–2,037.7 | 8.6–13.7 | 2,561.9 → 316.9 |
| 5,000 | Outcome + elapsed sort | 2,199.8–2,432.9 | 4.5–11.3 | 2,482.6 → 232.7 |

For each of the native before/after 5,000-row runs, table notifications decreased **5,002 → 204**, progress changes **5,002 → 204**, and log insertions **25,837 → 204**. This coalesces notifications, not result evidence: every source row and all 4,167 fake probe results remain intact, with 833 skipped rows. Exact chunk counts can vary with producer timing. Raw per-trial samples and dispatch maxima are retained in `target/ux-rescue-build/r7-before-native-1000.log`, `r7-before-native-5000.log`, `r7-after-native-1000.log`, and `r7-after-native-5000.log`.

### Changes and preserved contracts

- New `src/main/java/com/ivanocj/cameras/availability/presentation/swing/EdtEventDelivery.java`: a small operation-owned EDT FIFO, limited to **128 events per turn**, yielding through `invokeLater`. An ordered completion barrier runs only after pending rendering settles. Events/chunks retain submission order; no result is discarded or worker interrupted.
- `AvailabilityPanel.java`: single diagnostics and batch events use that helper. Each chunk performs one log append/trim/caret update, one bulk table notification, and one progress update where needed. Final summaries and control unlocking wait for both the original worker cleanup and queued rendering. Cancellation acknowledgment still cannot be masked by later progress, and terminal backend outcomes remain authoritative.
- `BatchResultsTableModel.java`: bulk snapshot storage keyed by the existing source lines, followed by one inclusive changed-row range notification. Repeated identities remain independent; existing sort/filter mapping, data types, source order, and all twelve values are unchanged.
- New `EdtEventDeliveryTest` and `BatchUpdateDeliveryTest`, extended model tests and exact single-log-tail characterization. Coverage includes bounded FIFO turns, UI action interleaving, immutable chunks, completion ordering, reentrant submission, failure propagation without stranded cleanup, filtered/sorted source identity, every final row before unlocking, late cancellation, exact log retention, and a fresh subsequent single check.
- `AvailabilityPreviewFactory.java` exposes the existing confined batch-panel fixture with an injected fake runner; the measurement fixture is not packaged into the executable.

No application/domain/protocol, credentials, dependencies, defaults, scheduling, or Preferences code changed. No auto-follow log toggle was added: sustained log-inspection demand was not established. The existing bounded tail remains the retention contract.

### Verification

| Check | Result | Repository-local evidence |
|---|---|---|
| Fresh confined baseline compile/full suite/package | **562 passed**, zero failures/errors/skips | `target/ux-rescue-build/r7-baseline-package.log` |
| Pre-extraction tests | Compilation failed for the missing delivery/bulk APIs, then **14 passed / 2 failed**: queued UI actions and cancellation could not interleave with the burst | `r7-red-compilation.log`; `r7-red-delivery-tests.log` |
| Initial targeted delivery/UI/cancellation/routing/architecture suite | **96 passed**, zero failures/errors/skips | `r7-targeted-tests.log` |
| Final Java 21 full suite/package, including additional default-limit/failure cases | **574 passed**, zero failures/errors/skips; executable JAR rebuilt | `r7-package.log`; `target/surefire-reports/` |
| Repeated critical ordering/cancellation/filter/routing regressions | **44 passed in each of three successive runs**, zero failures/errors/skips | `r7-repeat-1.log`; `r7-repeat-2.log`; `r7-repeat-3.log` |
| Native performance runs | **24 measured trials** plus warm-ups; every trial retained final data/counts and reported no asynchronous failure | Four before/after native logs above |
| Rebuilt JAR + final measurement fixture | PASS in both 5,000-row modes; headless data/completion checks only, not display verification | `r7-jar-preview.log` |
| JAR inspection | Delivery helper present; measurement/test classes absent; **zero native binaries**, one SLF4J provider descriptor | `target/campulse-1.0.0.jar` |
| Diff whitespace | PASS | `git diff --check` |

The confined Maven cache was absent in this checkout and was recreated using the previously approved Maven Central downloads under `target/ux-rescue-build/repository`; no external user settings/cache were reused. Subsequent builds/tests run offline. Packaging uses `-Dmaven.jar.forceCreation=true`; existing Shade module/manifest/license warnings remain outside this item. The native measurements do not verify physical keyboard, screen-reader, native chooser, or alternate-scale behavior; those earlier manual checks remain outstanding.

### Reproduce and manually inspect

After the confined package command above (add `-Dmaven.jar.forceCreation=true`), run from the repository root:

```sh
HOME="$PWD/target/ux-rescue-build/home" \
TMPDIR="$PWD/target/ux-rescue-build/tmp" \
java -Duser.home="$PWD/target" -Djava.io.tmpdir="$PWD/target" \
  -Djava.util.prefs.PreferencesFactory=com.ivanocj.cameras.availability.testsupport.InMemoryPreferencesFactory \
  -Djava.awt.headless=false \
  -cp "target/test-classes:target/campulse-1.0.0.jar" \
  com.ivanocj.cameras.availability.bootstrap.BatchResponsivenessPreview 5000 3
```

Use `1000 3` for the smaller workload. Each trial opens/closes its own native preview and prints CSV metrics; it never launches the production main, contacts a server/camera, opens a filesystem chooser, loads real credentials, or writes platform Preferences. Inputs stay under `target/ux-rescue-build/preview/`. `-Djava.awt.headless=true` is available for diagnostic-only runs, not display verification.

For interactive cancellation and log reading, use the existing confined desktop preview: expand Diagnostics, run a fake single/batch check, and cancel during work. Confirm acknowledgment is immediate, inputs stay locked through cleanup, and inspecting results or clearing diagnostics never starts another probe. No new keyboard command or persistence behavior was introduced.

**Next item at this checkpoint:** R8 — measure form load/save/flush latency, then obtain a persistence-timing decision before any off-EDT persistence implementation. It remains conditional; no timing change has been approved here.

## R8 — confined Preferences measurements and contract characterization

**Evaluated 2026-10-09 on `ux-rescue/r8-preferences-latency`, based on pushed R7 commit `6a22f83`; not merged. Conditional optimization skipped by user decision.** No production code or persistence timing changed. Native macOS Preferences latency is **not measured**: the existing repository-confinement boundary uses an in-memory Preferences factory and cannot establish platform-store performance. After reviewing the measurement boundary, the user chose **Keep current timing**; no native-store profiling or asynchronous implementation is authorized.

### Current contract protected before proposing a change

New `src/test/java/com/ivanocj/cameras/availability/presentation/swing/FormPersistenceTimingTest.java` adds five latch-driven cases:

- Startup `load()` runs on the EDT and restoration finishes before the panel is exposed; a queued UI task waits while the read is blocked.
- A submitted form writes all six existing keys in order and attempts synchronous flush on the EDT. A probe cannot start before that attempt returns, including when flush fails. Convenience persistence failures still do not become failed probes.
- Submitted snapshots do not change when the form is edited during work. Successive submissions retain the latest submitted state; invalid input never saves.
- A close event queued during flush waits for the current EDT action, then signals the newly owned probe token and waits for normal cleanup without interrupting the worker.

New `src/test/java/com/ivanocj/cameras/availability/testsupport/PreferencesTestStore.java` is an isolated, childless, in-memory Preferences SPI node with controllable get/put/flush hooks. It never accesses a platform store. These tests characterize **existing synchronous behavior**, not an approved asynchronous contract.

### Measurement scope and results

New `src/test/java/com/ivanocj/cameras/availability/bootstrap/PreferencesLatencyPreview.java` refuses to run unless the R0 in-memory factory is selected at JVM startup and verifies the actual root implementation before using it. `AvailabilityPreviewFactory.memoryPanel(...)` injects the real `PreferencesFormMemory` into the actual panel with fake probes and cancelled file/account choosers. The fixture is not packaged into the application.

Java **21.0.12.1**, native Swing windows (`headless=false`), 50 unmeasured store warm-up iterations per mode, three direct-operation trials per mode, one panel-load and three panel-save trials per mode. Store modes: the R0 in-memory factory, then explicit **SIMULATED** slow read, slow write, slow flush, and failing slow flush. Delays are requested at 250 ms; actual waits vary with the OS scheduler. An EDT pulse records how long an already-queued UI task waits. Call timing excludes pulse enqueueing and restoration assertions; pulse delay includes the enclosing EDT action, so panel-load also includes subsequent view/window setup.

| Store | Median direct load (ms) | Median direct save including flush (ms) | Median direct flush (ms) | Maximum panel-save queued pulse (ms) |
|---|---|---|---|---|
| In-memory baseline | 0.004 | 0.005 | 0.001 | 13.006 |
| **SIMULATED** slow read | 260.126 | 0.029 | 0.005 | 6.976 |
| **SIMULATED** slow write | 0.039 | 254.986 | 0.005 | 274.017 |
| **SIMULATED** slow flush | 0.028 | 260.087 | 258.848 | 269.465 |
| **SIMULATED** failing slow flush | 0.026 | 256.980 | 260.138 | 270.340 |

The simulated slow-read panel load took **259.992 ms**, with a queued pulse of **311.203 ms** including remaining view setup. All 15 fake checks verified off-EDT probing, save-return-before-probe ordering, and unchanged submitted identity/protocol/timeout/LAN settings. The failing-flush case still starts and completes its fake probe. These numbers demonstrate the synchronous path's sensitivity to a slow store; **they are not measurements of the native macOS backing store and do not justify claiming a real Preferences bottleneck**. Raw samples: `target/ux-rescue-build/r8-native-simulation.log`.

### Verification and reproduction

- Current-contract characterization and targeted Preferences/panel/bootstrap/architecture tests: **57 passed**, zero failures/errors/skips (`target/ux-rescue-build/r8-characterization-tests.log`; `r8-targeted-tests.log`).
- Measurement fixture compilation and native-window simulation: PASS; no asynchronous failures (`r8-measurement-compile.log`; `r8-native-simulation.log`).
- Full suite and executable JAR package: **579 passed**, zero failures/errors/skips (`target/ux-rescue-build/r8-package.log`). Existing Shade warnings are unchanged.
- Confinement guard: omission of the in-memory factory is rejected with exit code 1 before Preferences access (`r8-confinement-guard.log`).
- No changes under `src/main/`; no platform Preferences, production network probes, private credentials, or external user files are used.

After the confined package command (including `-Dmaven.jar.forceCreation=true`), run from the repository root:

```sh
HOME="$PWD/target/ux-rescue-build/home" \
TMPDIR="$PWD/target/ux-rescue-build/tmp" \
java -Duser.home="$PWD/target" -Djava.io.tmpdir="$PWD/target" \
  -Djava.util.prefs.PreferencesFactory=com.ivanocj.cameras.availability.testsupport.InMemoryPreferencesFactory \
  -Djava.awt.headless=false \
  -cp "target/test-classes:target/campulse-1.0.0.jar" \
  com.ivanocj.cameras.availability.bootstrap.PreferencesLatencyPreview 3 250
```

Arguments are repetitions and simulated delay in milliseconds. Omitting the in-memory factory property is deliberately rejected before opening Preferences. Headless runs are supported but do not verify display behavior. The confined preview never calls the production main or selects real account files.

**Resolved decision:** the user chose **Keep current timing** on 2026-10-09. Retain synchronous startup restoration, six-key snapshot writing, and save/flush-attempt-before-probe ordering. Skip the conditional off-EDT optimization and do not access the native store. The new tests protect this retained contract. Reopening R8 would require new evidence and explicit approval both for any native-store profiling outside the repository and for changes to probe/save ordering, snapshot serialization, or shutdown ownership.
