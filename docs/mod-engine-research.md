# Quest mod-engine research notes

These notes record the external behavior used by the backend implementation.
NFVR implements the relevant contracts independently; it does not copy code
from these projects.

## 2.3.1 correction: Gorilla Tag Quest is not PC Gorilla Tag

The selected Quest package is exactly `com.AnotherAxiom.GorillaTag`.  Review of
the current public evidence did **not** find an authoritative, versioned
Gorilla Tag Quest loader compatibility contract (loader version, game version,
and canonical destination together).  NFVR therefore registers Gorilla Tag
only as a conservative classification: a package-matched QMOD that explicitly
requests QuestLoader may use QuestPatcher's canonical loader destination *only
after* authenticated `modded.json` evidence says QuestLoader for the selected
app.  Scotland2 remains generic evidence only and does not authorize Gorilla
Tag.  Native/code payloads
are reported as `APK_PATCH_REQUIRED`; an ordinary Gorilla ZIP is reported as
unknown/unsupported with an actionable reason.  NFVR never invents a Gorilla
`Android/data`, `ModData`, or `Mods` path.

### Malachi Mod Manager (observed PC project, not a Quest implementation)

- Project: [Malachi Mod Manager](https://github.com/Malachi-the-modder/Mod-Manager)
- Release reviewed: [Mod_Manager](https://github.com/Malachi-the-modder/Mod-Manager/releases/tag/Mod_Manager)
- The repository/release describes a Windows desktop Gorilla Tag mod manager.
  Its installation model is PC Gorilla Tag with BepInEx, not a standalone
  Android/Meta Quest package.
- Observed PC artifacts are rooted at the Windows Gorilla Tag game directory
  (the directory containing `Gorilla Tag.exe`) and include
  `BepInEx/core/`, `BepInEx/plugins/`, `BepInEx/config/`,
  `BepInEx/LogOutput.log`, `winhttp.dll`, and `doorstop_config.ini`; the
  release also installs/updates its Windows-side `ModManager.exe`.  These are
  exact Windows filesystem names and loader artifacts, not Quest paths.
- It is not evidence of a Quest destination, Quest APK patch, QuestLoader,
  or Scotland2 compatibility.  No PC path, BepInEx class, executable, or
  release binary is used by NFVR's Quest engine.  The only lesson incorporated
  is architectural separation: a PC loader workflow must not be silently
  conflated with a standalone Quest workflow.
- Licensing/reuse caveat: the project and release were consulted as public
  provenance only; NFVR does not execute `ModManager.exe`, copy binaries, or
  copy source.  Verify the repository/release license and release contents
  again before making any future PC integration decision.

This finding is based on the repository and tagged release pages above, not on
the release's marketing wording alone.  Public repositories and releases can
change; the observation is time-sensitive and is not a claim that a future
release cannot add Quest support.

### Gorilla Tag Quest source review and recency caveat

The following independent sources were compared:

- [QuestPatcher](https://github.com/Lauriethefish/QuestPatcher) and its
  [QMOD specification](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/main/SPECIFICATION.md)
  for package binding, QMOD fields, and the authenticated `modded.json` tag.
- [QuestLoader](https://github.com/sc2ad/QuestLoader) as the loader ecosystem
  represented by QMOD's loader-relative fields.
- [Scotland2](https://github.com/sc2ad/scotland2) for its generic loader
  directory conventions.
- [Gorilla Tag Quest modding projects/searchable public repositories](https://github.com/search?q=Gorilla+Tag+Quest+loader&type=repositories)
  for current package/game-specific evidence.
- [Malachi Mod Manager](https://github.com/Malachi-the-modder/Mod-Manager) and
  its tagged release for the separate Windows/BepInEx ecosystem.

QuestPatcher and Scotland2 document generic mechanisms and Beat Saber-oriented
examples; they do not establish that current `com.AnotherAxiom.GorillaTag`
accepts those loaders or paths.  In particular, Scotland2 documentation is
not treated as Gorilla compatibility evidence.  The engine consequently
accepts no guessed Gorilla destination and makes APK patching a classification
only, not an operation.  These sources were reviewed for this 2.3.1
correction; GitHub content, game versions, and loader compatibility are
mutable, so this is a dated evidence boundary rather than a promise of
ongoing compatibility.

An archive that explicitly contains
`Android/data/com.AnotherAxiom.GorillaTag/...` is different from a discovered
`Mods`/`plugins`/`ModData` directory: the universal Android-layout rule binds
every mapping to the exact selected package and does not infer a mod
destination. That explicit package-bound layout remains allowed. Directory
discovery is read-only evidence only and is rejected for Gorilla Tag before
confirmation; confirmation cannot override the non-authorizing profile.

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
