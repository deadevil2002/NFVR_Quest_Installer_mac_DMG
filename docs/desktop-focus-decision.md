# Desktop focus and framework decision

The Windows acceptance report still reproduced `ActiveParent with no focused
child` after replacing Swing with AWT. A chooser-only explanation is therefore
not sufficient. The dynamic Mods search/list and tab/settings transitions can
remove a focused descendant from the Compose tree. This is the application-level
lifecycle risk addressed here; without the failing Windows stack trace and a
Windows reproduction, it is not a proven exclusive root cause.

Clear Compose focus on the UI dispatcher before those state transitions. Do not
restore native focus manually. Windows pickers run in a separate STA process and
return validated paths, so their dialog lifecycle is outside the Compose process.

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