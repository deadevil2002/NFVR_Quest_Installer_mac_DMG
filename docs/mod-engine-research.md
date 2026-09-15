# Quest mod-engine research notes

These notes record the external behavior used by the backend implementation.
NFVR implements the relevant contracts independently; it does not copy code
from these projects.

## QuestPatcher QMOD

- [QMOD format specification](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/main/SPECIFICATION.md)
- [QMOD JSON schema](https://raw.githubusercontent.com/Lauriethefish/QuestPatcher.QMod/main/QuestPatcher.QMod/Resources/qmod.schema.json)
- [QuestPatcher `ModManager`](https://raw.githubusercontent.com/Lauriethefish/QuestPatcher/main/QuestPatcher.Core/Modding/ModManager.cs)
- [QuestPatcher `QPMod` installation](https://raw.githubusercontent.com/Lauriethefish/QuestPatcher/main/QuestPatcher.Core/Modding/QPMod.cs)
- [QMOD `CopyExtension` model](https://raw.githubusercontent.com/Lauriethefish/QuestPatcher.QMod/main/QuestPatcher.QMod/CopyExtension.cs)

The schema identifies `modFiles`, `lateModFiles`, `libraryFiles`,
`fileCopies`, `copyExtensions`, `dependencies`, `packageId`,
`packageVersion`, and the `QuestLoader`/`Scotland2` loader values.  The
reference installer uses these destinations:

The current published schema enumerates `_QPVersion` values `0.1.0`,
`0.1.1`, `0.1.2`, `1.0.0`, `1.1.0`, and `1.2.0`, and requires `name`, `id`,
`author`, and SemVer `version`.  NFVR accepts those versions and a narrowly
compatible legacy manifest with no `_QPVersion`; an unknown version or
malformed required field is rejected.  `porter` is metadata and is retained.
Dependency entries use `id`, version range, `downloadIfMissing`, and
`required`; `required:false` is treated as optional, while NFVR never follows
the URL.  `copyExtensions` is the canonical array of
`extension`/`destination` records.  QuestPatcher uses these records to
register a file destination with its file-copy manager; they are not an
instruction to copy every matching archive entry during QMOD installation.
NFVR does not persist that QuestPatcher registration, so a non-empty
`copyExtensions` declaration is blocked rather than silently misinterpreted.
Only canonical `fileCopies` records are direct copy instructions.

- QuestLoader mods/libs: `/sdcard/Android/data/<package>/files/mods` and
  `/sdcard/Android/data/<package>/files/libs`.
- Scotland2 early/late/libs:
  `/sdcard/ModData/<package>/Modloader/early_mods`,
  `/sdcard/ModData/<package>/Modloader/mods`, and
  `/sdcard/ModData/<package>/Modloader/libs`.

NFVR only uses these destinations after package binding and read-only loader
evidence.  Dependencies and APK patching are requirements, not operations:
NFVR does not download, execute, or patch them.

## MarrowSDK / BONELAB

- [MarrowSDK pallet packing and local Quest installation](https://github.com/StressLevelZero/MarrowSDK/blob/main/Docs/BuildPallet.md)

The SDK documents copying the complete generated pallet folder into
`Android/data/com.StressLevelZero.BONELAB/files/Mods`.  The analyzer preserves
complete folder structure, recognizes Quest/native versus desktop markers,
and does not treat a desktop payload as a Quest install.

## LemonLoader / MelonLoader

- [LemonLoader installer repository](https://github.com/LemonLoader/MelonLoaderInstaller)
- [MelonLoader Quest-compatible project](https://github.com/LemonLoader/MelonLoader_057)

Loader status in NFVR is evidence-only.  Free-form `dumpsys`, `pm`, and
directory names are not accepted as proof because package labels and mod
files can contain arbitrary loader-looking text.  The default desktop
detector now performs a bounded, read-only pull of the exact base APK returned
by `pm path`, checks its `stat` size, and parses only the documented
`modded.json` QuestPatcher tag with its canonical patcher identity/version and
loader name (and validates the optional loader version when present).  That
authenticated tag supports QuestLoader and Scotland2.  No authoritative APK
tag contract for LemonLoader/MelonLoader is claimed, so those statuses remain
`UNKNOWN`.  Unknown evidence is fail-closed immediately before a write.  NFVR
never patches or mutates the APK/data tree.
No native library filename is treated as a bootstrap marker unless a future
documented artifact schema makes it authoritative.

## Resolution and safety policy

The engine resolves package strategies in a fixed order:

1. `NFVR_MANIFEST`
2. `QMOD`
3. explicit `Android/data` or `Android/obb` layout (including
   `sdcard/Android/...` archive prefixes)
4. authenticated loader evidence
5. a profile in `mod-profiles.json`
6. a directory found by the selected-package, read-only discovery pass
7. built-in game content
8. unknown

An analysis records the winning strategy, confidence, and evidence.  Directory
existence is never loader authentication.  A generic existing-directory
proposal is marked `EXPLICIT_CONFIRMATION_REQUIRED`, displays its exact
destination, and cannot be made writable without the caller presenting and
confirming the immutable token.

`ModOperationBinding` binds a write to the ADB serial, package ID, installed
version, archive SHA-256, and analysis plan ID.  The manager rechecks all of
these values, re-analyzes the archive, rechecks loader evidence immediately
before the first push, and verifies remote file existence and sizes before
reporting success.  APK patching is represented by `APK_PATCH_REQUIRED` and
the `ApkModLoaderPatcher` assessment interface only; this release has no APK
patch implementation.

## Profile and source/license evidence

Profiles are data, not Kotlin destination heuristics:

- BONELAB uses the local Quest pallet destination documented by the
  [Stress Level Zero MarrowSDK BuildPallet guide](https://github.com/StressLevelZero/MarrowSDK/blob/main/Docs/BuildPallet.md).
- Beat Saber loader-relative destinations are limited to the paths documented
  by [QuestPatcher](https://github.com/Lauriethefish/QuestPatcher) and
  [scotland2](https://github.com/sc2ad/scotland2).  The profile does not claim
  that a loader is installed; the authenticated APK tag is still required.

Release support is intentionally narrower than the legacy game-name list:

| Ecosystem | Exact package ID | Status |
| --- | --- | --- |
| BONELAB | `com.StressLevelZero.BONELAB` | Supported known profile; authoritative Marrow pallet evidence |
| Beat Saber | `com.beatgames.beatsaber` | Supported profile with open-source destination/loader evidence; authenticated loader evidence still required |
| Blade & Sorcery: Nomad | none registered | Blocked as a known profile; no verified package/path/manifest contract |
| Any other package | selected package only | Generic existing-directory installs require a fresh read-only candidate and exact confirmation; no guessed game profile |

Blade & Sorcery: Nomad is deliberately **not** a registered ecosystem in this
release.  The previously cited `https://www.bladeandsorcery.com/modding`
returned HTTP 404 during release review, and the reachable official SDK
documentation at [KospY/BasSDK](https://github.com/KospY/BasSDK) states that
the SDK moved to [Warpfrog's Azure repository](https://dev.azure.com/Warpfrog/BasSDK/_git/BasSDK).
The reachable SDK/wiki identifies the game and links to Nomad mod
distribution, but does not provide a case-sensitive Android package ID,
`Android/data` mod path, or APK/manifest signature.  Therefore neither
`com.WarpFrog.BladeAndSorcery` nor the differently cased
`com.Warpfrog.BladeAndSorcery` is treated as an authoritative profile.
Nomad-specific profile installs are blocked; only an explicitly targeted
manifest or a read-only discovered directory with exact user confirmation
can proceed under the generic safety rules.

NFVR does not copy source code from these projects.  The profile file stores
URLs as provenance only and does not download them or expose mod-download
websites in the UI.  All engine code is an independent implementation under
the repository's existing project license.
