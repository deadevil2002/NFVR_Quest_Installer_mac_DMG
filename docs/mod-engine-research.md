# Quest mod-engine research notes

These notes record the external behavior used by the backend implementation.
NFVR implements the relevant contracts independently; it does not copy code
from these projects.

## QuestPatcher QMOD

- [QMOD format specification](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/main/SPECIFICATION.md)
- [QMOD JSON schema](https://raw.githubusercontent.com/Lauriethefish/QuestPatcher.QMod/main/QuestPatcher.QMod/Resources/qmod.schema.json)
- [QuestPatcher `ModManager`](https://raw.githubusercontent.com/Lauriethefish/QuestPatcher/main/QuestPatcher.Core/Modding/ModManager.cs)
- [QuestPatcher `QPMod` installation](https://raw.githubusercontent.com/Lauriethefish/QuestPatcher/main/QuestPatcher.Core/Modding/QPMod.cs)

The schema identifies `modFiles`, `lateModFiles`, `libraryFiles`,
`fileCopies`, `copyExtensions`, `dependencies`, `packageId`,
`packageVersion`, and the `QuestLoader`/`Scotland2` loader values.  The
reference installer uses these destinations:

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
