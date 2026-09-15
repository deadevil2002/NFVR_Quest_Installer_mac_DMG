# Desktop focus and picker decision

## 2.2.1 acceptance evidence

The proven legacy picker problem was its `System.Windows.Forms.FolderBrowserDialog`
UI/result transport and the subsequent path canonicalization boundary. It was
not a direct Compose-owned dialog, and did not provide the focused Compose
window with an explicit owner/result lifecycle. Scanning AWT windows and
restoring focus manually did not provide a reliable ownership contract.
`ActiveParent with no focused child` remains a separate Compose conditional
subtree/lifecycle risk; this report does **not** claim that the legacy picker is
its exclusive root cause.

Version 2.2.1 replaces that path with a direct JNA `IFileOpenDialog` adapter.
The adapter uses the COM folder/file flags, filesystem-only result, ZIP filter,
one dedicated daemon STA executor, and explicit COM pointer/task-memory
cleanup. The Compose side clears focus on its UI dispatcher, yields once
before opening, gates one picker at a time, and disables tab/settings/picker
actions during the dialog. Folder and ZIP results are normalized and validated
without canonicalization.

The pinned dependencies are:

- `net.java.dev.jna:jna:5.17.0`
- `net.java.dev.jna:jna-platform:5.17.0`

JNA 5.17.0 is distributed under its BSD/Apache dual license. Sources and
license text are available from the JNA project:
https://github.com/java-native-access/jna
and the Maven Central artifacts:
https://central.sonatype.com/artifact/net.java.dev.jna/jna/5.17.0

The completed unit tests cover COM argument ordering, delayed-task gate
lifecycle, selected/cancelled/failed adapter mapping, normalized readable path
validation, ZIP selection dispatch with selected-game preservation, and focus
ordering. The ZIP orchestration test also verifies state/persistence precede
exactly one analyzer call and that cancel/busy/failure do not analyze. COM UI
interaction is intentionally not faked in unit tests.

## Windows retest limitation

This environment cannot display a real Windows COM dialog or validate focus
ownership in a packaged Windows build. A Windows retest is still required for
folder selection, ZIP filtering, cancellation (`0x800704C7`), Unicode paths,
taskbar/owner activation, and close/reopen behavior. The automated checks
provide evidence for ordering and cleanup paths, not a substitute for that
interactive Windows acceptance run.

## Framework review

Reviewed official sources:

- https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html
- https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.8.2

The compatibility guide documents that Compose 1.8+ requires Kotlin 2.1.0+
and a matching Kotlin/Compose compiler plugin version. The existing application
uses Compose 1.7.3 and Kotlin/compiler plugin 2.0.21. The 1.8.2 release notes
include text-field and desktop fixes, but do not establish that this exact
Windows ActiveParent reproduction is fixed.

Retain 1.7.3/2.0.21 for this release rather than combine the mod-engine changes
with a compiler/runtime migration lacking Windows packaging and focus evidence.
This is a risk decision, not a claim that 1.7.3 is current or free of focus bugs.
JDK 17 and the Swing Main dispatcher remain unchanged. A later framework upgrade
should be tested independently with Windows text-field, tab, settings, picker,
and packaged-installer acceptance.