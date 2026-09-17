# Quest mod-installation research

**Purpose.** This is a source/evidence report prepared before an NFVR
architecture decision. It records what the inspected projects do and what
they do **not** establish. It is not an implementation report and it does
not promote any researched algorithm, loader, binary, or game version into an
NFVR capability.

## Executive recommendations

1. Keep game preparation (APK inspection, backup, patching, signing,
   reinstall, restore, and loader verification) separate from mod-package
   installation (QMOD, game content, BONELAB content/code, or raw loader
   files).
2. Make preparation profile- and version-bound. A generic QuestLoader,
   Scotland2, or LemonLoader mechanism is not proof that a particular
   `com.AnotherAxiom.GorillaTag`, BONELAB, or Beat Saber APK is compatible.
3. Require an exact installed package/version/ABI/resource match before any
   destructive operation. Unknown Gorilla Tag compatibility must remain
   blocked with the technical reason, rather than being converted into either
   a blanket “cannot mod” or a blanket “supported” claim.
4. Implement QMOD archive validation and the verified destination mappings,
   but do not reinterpret `copyExtensions` as an instruction to copy archive
   files. It registers an import destination in QuestPatcher's model.
5. Independently implement reviewed behavior. No upstream source, release
   executable, APK, native library, mod menu, or other binary was imported
   into this NFVR documentation change.

## Scope, method, and evidence rules

The following repositories were inspected at the exact commits in the source
table below. The checkouts were under `/tmp/nfvr-src`; the work was static
source, license, diff, archive, and PE-metadata review. No release executable,
DLL, APK, native `.so`, agent, or unknown binary was executed. No device,
installed APK, or current game version was available. A repository README,
resource URL, package identifier, or directory convention is therefore not
treated as compatibility proof.

Evidence ranks used here:

* **AUTHORITATIVE_UPSTREAM** — primary source/specification for the behavior it
  actually implements; still not proof of another game's current version.
* **MAINTAINED_OPEN_SOURCE** — useful maintained source, but game/version or
  production scope is narrower.
* **HISTORICAL_REFERENCE** — useful historical workflow/content evidence.
* **COMMUNITY_EXPERIMENTAL** — unverified community claim; cannot enable
  production preparation.
* **UNTRUSTED_BINARY** — static/platform evidence only; no executable or
  release artifact is trusted.

“Source algorithm” below means a behavior observed in the inspected source.
“NFVR capability” means an operation already present in this client. This
report establishes no new preparation or installation capability; it is an
architecture input only.

## Source register and corrected discrepancies

| Source and exact commit | License in the inspected checkout | Scope and evidence rank |
|---|---|---|
| [QuestPatcher](https://github.com/Lauriethefish/QuestPatcher/tree/1c7d2ca404563d920488b949f58aee773f1497d9), `1c7d2ca404563d920488b949f58aee773f1497d9` | Custom permissive, zlib-style three-clause notice in [`LICENSE`](https://github.com/Lauriethefish/QuestPatcher/blob/1c7d2ca404563d920488b949f58aee773f1497d9/LICENSE); do not label it SPDX `zlib` without preserving the exact text | Generic Quest IL2CPP patcher; **AUTHORITATIVE_UPSTREAM** for its own algorithm |
| [QuestPatcher.QMod](https://github.com/Lauriethefish/QuestPatcher.QMod/tree/eadb8d8d21caa1f8586b61da3c950a2953ebd399), `eadb8d8d21caa1f8586b61da3c950a2953ebd399` | Same custom notice in [`LICENSE`](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/eadb8d8d21caa1f8586b61da3c950a2953ebd399/LICENSE) | QMOD specification, schema, and model; **AUTHORITATIVE_UPSTREAM** for format semantics |
| [QuestLoader](https://github.com/sc2ad/QuestLoader/tree/cf8408b9200bcc85d38b0a356e1cf831a14c9747), `cf8408b9200bcc85d38b0a356e1cf831a14c9747` | MIT, [`LICENSE`](https://github.com/sc2ad/QuestLoader/blob/cf8408b9200bcc85d38b0a356e1cf831a14c9747/LICENSE) | Quest/Android native loader; **MAINTAINED_OPEN_SOURCE** / historical release evidence |
| [Scotland2](https://github.com/sc2ad/scotland2/tree/003f28dd6e47285cf441a1a195206e94ec44971b), `003f28dd6e47285cf441a1a195206e94ec44971b` | MIT, [`LICENSE`](https://github.com/sc2ad/scotland2/blob/003f28dd6e47285cf441a1a195206e94ec44971b/LICENSE) | Beat Saber-oriented Quest loader; **MAINTAINED_OPEN_SOURCE**, not a Gorilla profile |
| [ModsBeforeFriday](https://github.com/Lauriethefish/ModsBeforeFriday/tree/4a953cb25546ee157db67a1b650c83f9c9afe402), `4a953cb25546ee157db67a1b650c83f9c9afe402` | AGPL-3.0 in [`LICENSE`](https://github.com/Lauriethefish/ModsBeforeFriday/blob/4a953cb25546ee157db67a1b650c83f9c9afe402/LICENSE); `mbf-axml/LICENSE` is separately MIT | Beat Saber on-device agent/service; **MAINTAINED_OPEN_SOURCE**, architecture evidence only |
| [MelonLoaderInstaller](https://github.com/LemonLoader/MelonLoaderInstaller/tree/ac443fce9f2ef890caddf8c64bba9194a43361bd), `ac443fce9f2ef890caddf8c64bba9194a43361bd` | GPL-3.0, [`LICENSE`](https://github.com/LemonLoader/MelonLoaderInstaller/blob/ac443fce9f2ef890caddf8c64bba9194a43361bd/LICENSE); README credits additional components | Android/LemonLoader installer; **MAINTAINED_OPEN_SOURCE**, not a current BONELAB matrix |
| [MelonLoader](https://github.com/LavaGang/MelonLoader/tree/982ed9981ac5b0619648a2e79bcc02fd8861db33), `982ed9981ac5b0619648a2e79bcc02fd8861db33` | Apache-2.0, [`LICENSE.md`](https://github.com/LavaGang/MelonLoader/blob/982ed9981ac5b0619648a2e79bcc02fd8861db33/LICENSE.md) | General Unity loader; Android/Quest explicitly WIP; **MAINTAINED_OPEN_SOURCE** |
| [BoneLib](https://github.com/yowchap/BoneLib/tree/c6470de53299b9d3adb6d1f62383177a812cbacd), `c6470de53299b9d3adb6d1f62383177a812cbacd` | GPL-3.0, [`LICENSE.md`](https://github.com/yowchap/BoneLib/blob/c6470de53299b9d3adb6d1f62383177a812cbacd/LICENSE.md) | BONELAB managed-code library, not a patcher; **MAINTAINED_OPEN_SOURCE** |
| [GorillaTag-Modding-Guide](https://github.com/burritosoftware/GorillaTag-Modding-Guide/tree/4180d19c45c06814374cc9737e671e785d805395), `4180d19c45c06814374cc9737e671e785d805395` | ISC, [`LICENSE.md`](https://github.com/burritosoftware/GorillaTag-Modding-Guide/blob/4180d19c45c06814374cc9737e671e785d805395/LICENSE.md) | Historical Quest/PC procedures; **HISTORICAL_REFERENCE** |
| [QuestPatcherWORKING](https://github.com/QuestModsDev/QuestPatcherWORKING/tree/5365fe24a5894ce7d47ff21b293db85599de9500), `5365fe24a5894ce7d47ff21b293db85599de9500` | No checked-in governing license; API metadata was not treated as a license | Current-Gorilla claim with almost no implementation delta; **COMMUNITY_EXPERIMENTAL / UNTRUSTED** |
| [Malachi Mod-Manager](https://github.com/Malachi-the-modder/Mod-Manager/tree/2ef1ae2fe250a94f59bae0f93825b5ab1e21aa26), `2ef1ae2fe250a94f59bae0f93825b5ab1e21aa26` | No checked-in license | Windows PC BepInEx artifacts; **UNTRUSTED_BINARY** |
| [GT_CustomMapExample](https://github.com/Another-Axiom/GT_CustomMapExample/tree/02bac82f9d6a6f79baf7f4c73e01ed3e4d2b3c56), `02bac82f9d6a6f79baf7f4c73e01ed3e4d2b3c56` | CC BY-NC-SA 4.0, [`LICENSE`](https://github.com/Another-Axiom/GT_CustomMapExample/blob/02bac82f9d6a6f79baf7f4c73e01ed3e4d2b3c56/LICENSE) | Gorilla built-in custom-map content; **HISTORICAL_REFERENCE / content source** |

Two source-review discrepancies are resolved by the checked-in files rather
than by repository metadata: QuestPatcher and QMOD use the custom
zlib-style notice shown above (not an unqualified standard zlib label), and
LavaGang/MelonLoader is Apache-2.0 while MelonLoaderInstaller is GPL-3.0.
The fork diff also resolves the conflicting direction reported elsewhere:
upstream uses LibMainLoader `v0.2.0` and Scotland2 `v0.1.7`; the fork changes
those URLs backwards to `v0.1.0-alpha` and `v0.1.4`. Both trees say
`supportedVersions >=2.2.2`; that is the QuestPatcher release/resource
configuration, not a Gorilla Tag game-version range.

## Per-ecosystem evidence records

The fields in each record are deliberately repeated so a future profile
cannot omit platform, format, preparation, preservation, compatibility, or
verification information.

### QuestPatcher: generic APK preparation

* **Repository / commit / license:** [QuestPatcher at
  `1c7d2ca...`](https://github.com/Lauriethefish/QuestPatcher/tree/1c7d2ca404563d920488b949f58aee773f1497d9);
  custom three-clause permissive notice, as above.
* **Maintenance, target, package, engine, ABI:** maintained upstream-style
  source for Android/Meta Quest Unity IL2CPP applications; package is
  selected dynamically (the source is not a current-game allowlist);
  `arm64-v8a` and `armeabi-v7a` paths are handled, with loader resources often
  arm64-focused.
* **Format and loader:** APK ZIP with binary `AndroidManifest.xml` (AXML);
  QuestLoader or Scotland2, recorded in `modded.json`; native `.so` loader
  assets are injected into `lib/<ABI>/`.
* **Preparation, APK patch, signing, reinstall:** preparation is required;
  `PatchingManager` copies the installed APK to a work path, edits AXML,
  injects ABI-specific libraries/resources, writes the loader marker, signs,
  then the install path uninstalls and installs the replacement. A re-signed
  package cannot be treated as a normal store update.
* **Data and OBB behavior:** `InstallManager`/`PatchingManager` attempt to
  move application external data and OBB content under a QuestPatcher backup,
  uninstall, then restore and recreate loader directories. Backup/restore
  failures are logged rather than providing a journaled all-or-nothing
  transaction; arbitrary app-private data restoration is not guaranteed.
* **Destinations:** QuestLoader:
  `/sdcard/Android/data/<package>/files/{mods,libs}/`; Scotland2:
  `/sdcard/ModData/<package>/Modloader/{early_mods,mods,libs}/`.
* **Version compatibility and verification:** detects package/version/ABI,
  loader markers, and configured resource/version lookups. A configured
  `supportedVersions` value is not a game compatibility matrix; exact
  unstripped Unity/resource availability is the meaningful gate. Verification
  is package/status/tag based, not runtime proof.
* **Rollback:** backup/restore helpers exist, but uninstall precedes install
  and there is no complete transaction or guaranteed automatic rollback.
  NFVR must add immutable hashes, stale-device/version checks, and explicit
  partial-restore states.
* **Useful code paths / citations:** [`PatchingManager.cs`](https://github.com/Lauriethefish/QuestPatcher/blob/1c7d2ca404563d920488b949f58aee773f1497d9/QuestPatcher.Core/Patching/PatchingManager.cs),
  [`InstallManager.cs`](https://github.com/Lauriethefish/QuestPatcher/blob/1c7d2ca404563d920488b949f58aee773f1497d9/QuestPatcher.Core/InstallManager.cs),
  [`AndroidDebugBridge.cs`](https://github.com/Lauriethefish/QuestPatcher/blob/1c7d2ca404563d920488b949f58aee773f1497d9/QuestPatcher.Core/AndroidDebugBridge.cs),
  [`ApkZip.cs`](https://github.com/Lauriethefish/QuestPatcher/blob/1c7d2ca404563d920488b949f58aee773f1497d9/QuestPatcher.Zip/ApkZip.cs),
  [`ModManager.cs`](https://github.com/Lauriethefish/QuestPatcher/blob/1c7d2ca404563d920488b949f58aee773f1497d9/QuestPatcher.Core/Modding/ModManager.cs).
* **NFVR decision / confidence:** use only as an algorithm reference and
  profile model; independently implement stronger backup/rollback and
  resource gates. **AUTHORITATIVE_UPSTREAM for generic mechanics; no current
  Gorilla capability established.**

### QuestPatcher.QMod: format and mapped installation

* **Repository / commit / license:** [QMod at
  `eadb8d8...`](https://github.com/Lauriethefish/QuestPatcher.QMod/tree/eadb8d8d21caa1f8586b61da3c950a2953ebd399);
  the same custom permissive notice.
* **Maintenance, target, engine, ABI:** maintained format source for Quest
  Unity mods; target package is the manifest's `packageId`, so engine/ABI are
  delegated to the prepared game and are not implied by QMOD.
* **Format and loader:** PKWARE ZIP without ZIP64 and exactly one full-name
  `mod.json`; schema/parser revisions accept `_QPVersion` 0.1.0, 0.1.1,
  0.1.2, 1.0.0, 1.1.0, and 1.2.0. Loader is `QuestLoader` or `Scotland2`.
  Fields are `modFiles`, `lateModFiles`, `libraryFiles`, `fileCopies`,
  `copyExtensions`, `dependencies`, `packageId`, and `packageVersion`.
* **Preparation, APK patch, signing, reinstall:** QMOD is a package layer;
  it does not patch/sign/reinstall an APK. The selected game must already
  satisfy the loader's preparation requirement.
* **Data and OBB behavior:** QMOD installation itself does not back up or
  restore app data/OBB; those belong to preparation.
* **Destinations and semantics:** QuestLoader libraries/mods map to
  `/sdcard/Android/data/<id>/files/libs` and `.../files/mods`.
  Scotland2 libraries/early/late mods map to
  `/sdcard/ModData/<id>/Modloader/libs`,
  `.../early_mods`, and `.../mods`. `fileCopies[].destination` is an
  explicit destination. `copyExtensions` registers a later extension
  destination; it is not a current-archive copy loop.
* **Version compatibility and verification:** schema validation, required
  identity fields, package ID, semver dependency ranges, required/optional
  dependencies, and cycle detection are supported. QuestPatcher's provider
  checks package ID but the inspected source does not make `packageVersion`
  an installed-game compatibility gate; NFVR must do so explicitly. Verify
  canonical entries, source hashes/size, remote destinations, and loader
  readiness.
* **Rollback:** no package-level rollback. Remove only mapped files and do not
  delete libraries still used by another mod; APK/data rollback remains the
  preparation layer's responsibility.
* **Useful code paths / citations:** [`SPECIFICATION.md`](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/eadb8d8d21caa1f8586b61da3c950a2953ebd399/SPECIFICATION.md),
  [`qmod.schema.json`](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/eadb8d8d21caa1f8586b61da3c950a2953ebd399/QuestPatcher.QMod/Resources/qmod.schema.json),
  [`QMod.cs`](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/eadb8d8d21caa1f8586b61da3c950a2953ebd399/QuestPatcher.QMod/QuestPatcher.QMod/QMod.cs),
  [`QModManifest.cs`](https://github.com/Lauriethefish/QuestPatcher.QMod/blob/eadb8d8d21caa1f8586b61da3c950a2953ebd399/QuestPatcher.QMod/QModManifest.cs),
  [`QPMod.cs`](https://github.com/Lauriethefish/QuestPatcher/blob/1c7d2ca404563d920488b949f58aee773f1497d9/QuestPatcher.Core/Modding/QPMod.cs).
* **NFVR decision / confidence:** implement only validated manifest/mapping
  semantics, ZIP traversal/duplicate/ZIP64 rejection, explicit
  `packageVersion` policy, and an allowlist for arbitrary destinations.
  **AUTHORITATIVE_UPSTREAM for format; no installed-game capability claimed.**

### QuestLoader

* **Repository / commit / license:** [QuestLoader at
  `cf8408b...`](https://github.com/sc2ad/QuestLoader/tree/cf8408b9200bcc85d38b0a356e1cf831a14c9747);
  MIT.
* **Maintenance, target, package, engine, ABI:** maintained/historical
  Quest/Android Unity IL2CPP native-loader source; package ID is discovered
  from the running app rather than fixed; native build inputs include
  `arm64-v8a` and `armeabi-v7a`, while the inspected patch script is
  arm64-focused.
* **Format and loader:** copied APK ZIP with native `.so` entries; injected
  `libmain.so` initializes and bridges to `libmodloader.so`, which loads
  external native libraries/mods.
* **Preparation, APK patch, signing, reinstall:** patching is required;
  `patch.ps1`/`tools/apkmod.ps1` add libraries under `lib/<ABI>/` and sign
  the copied APK. QuestLoader itself does not define the complete
  uninstall/reinstall transaction; QuestPatcher supplies that surrounding
  flow.
* **Data and OBB behavior:** no complete QuestLoader backup/restore contract;
  use the preparation engine's explicit data/OBB policy.
* **Destinations:** app external files, conventionally
  `/sdcard/Android/data/<package>/files/mods` and `.../libs`; the loader
  copies `.so` files to app-private temporary storage before loading.
* **Version compatibility and verification:** ABI, Unity initialization,
  loader resources, and game-native behavior must match the installed APK.
  The source has no contemporary Gorilla version matrix. Verification must
  inspect package/version, injected entries, loader marker, destination
  hashes, and a compatible runtime probe.
* **Rollback:** not supplied as a complete loader transaction; retain original
  APK/split/OBB/data metadata and rollback outside the loader.
* **Useful code paths / citations:** [`patch.ps1`](https://github.com/sc2ad/QuestLoader/blob/cf8408b9200bcc85d38b0a356e1cf831a14c9747/patch.ps1),
  [`apkmod.ps1`](https://github.com/sc2ad/QuestLoader/blob/cf8408b9200bcc85d38b0a356e1cf831a14c9747/tools/apkmod.ps1),
  [`libmain`](https://github.com/sc2ad/QuestLoader/tree/cf8408b9200bcc85d38b0a356e1cf831a14c9747/libmain),
  [`modloader.cpp`](https://github.com/sc2ad/QuestLoader/blob/cf8408b9200bcc85d38b0a356e1cf831a14c9747/libmodloader/src/modloader.cpp).
* **NFVR decision / confidence:** independently reimplement only after
  pinned ABI/resource/game-profile evidence; never fetch unpinned release
  libraries implicitly. **MAINTAINED_OPEN_SOURCE mechanism evidence, not
  Gorilla compatibility evidence.**

### Scotland2

* **Repository / commit / license:** [Scotland2 at
  `003f28d...`](https://github.com/sc2ad/scotland2/tree/003f28dd6e47285cf441a1a195206e94ec44971b);
  MIT.
* **Maintenance, target, package, engine, ABI:** maintained Quest/Android
  loader explicitly documented for Beat Saber; package examples are
  Beat Saber (`com.beatgames.beatsaber`), not Gorilla Tag; source is
  IL2CPP/ARM64-oriented. No Gorilla package/version matrix is present.
* **Format and loader:** external native `libsl2.so` plus ELF `.so` libraries
  and mods, separated into early/late phases.
* **Preparation, APK patch, signing, reinstall:** a compatible profile needs
  LibMain/manifest preparation and signing in the surrounding patcher; the
  loader source alone does not perform APK mutation, signing, or reinstall.
* **Data and OBB behavior:** no complete backup/restore contract; preparation
  must own it.
* **Destinations:** `/sdcard/ModData/<APP ID>/Modloader/libsl2.so`,
  `libs/`, `early_mods/`, and `mods/` beneath that directory.
* **Version compatibility and verification:** dependency parsing,
  deterministic ordering, topological sorting, and `il2cpp_init`/first-scene
  load phases are source evidence. They are not version compatibility
  evidence. Verify exact Beat Saber profile/resource, external hashes, and a
  loader runtime status.
* **Rollback:** no full transaction; external files can be removed, but APK
  restoration belongs to the preparation layer.
* **Useful code paths / citations:** [`README.md`](https://github.com/sc2ad/scotland2/blob/003f28dd6e47285cf441a1a195206e94ec44971b/README.md),
  [`main.cpp`](https://github.com/sc2ad/scotland2/blob/003f28dd6e47285cf441a1a195206e94ec44971b/src/main.cpp),
  [`loader.cpp`](https://github.com/sc2ad/scotland2/blob/003f28dd6e47285cf441a1a195206e94ec44971b/src/loader.cpp).
* **NFVR decision / confidence:** Beat Saber-only profile candidate; do not
  infer Gorilla support from `<APP ID>` paths. **MAINTAINED_OPEN_SOURCE, narrow
  game evidence.**

### ModsBeforeFriday (MBF)

* **Repository / commit / license:** [MBF at
  `4a953cb...`](https://github.com/Lauriethefish/ModsBeforeFriday/tree/4a953cb25546ee157db67a1b650c83f9c9afe402);
  AGPL-3.0; `mbf-axml` is separately MIT.
* **Maintenance, target, package, engine, ABI:** maintained Beat Saber
  (`com.beatgames.beatsaber`) Quest tooling; Unity IL2CPP; patch resources
  and native files are arm64-oriented. It is not a general Gorilla agent.
* **Format and loader:** Rust agent protocol uses one JSON request on stdin
  and newline-delimited JSON responses/logs; APK/OBB/AXML plus native
  resources; status tracks loader, OBB, version, core mods, and readiness.
* **Preparation, APK patch, signing, reinstall:** agent kills the game,
  copies the APK to temporary storage, applies exact-version resource or
  downgrade diffs, rewrites binary AXML, injects `libmain.so`/unstripped
  `libunity.so`, records `modded.json`, signs APK v2, uninstalls/reinstalls,
  grants storage access, and restores OBB.
* **Data and OBB behavior:** OBB files are backed up/restored. PlayerData is
  backed up but intentionally not automatically restored in the inspected
  path because permissions can cause a black screen. Downgrade may require
  DLC redownload; output checksums are not fully verified after every diff.
* **Destinations:** `/data/local/tmp/mbf-agent`, upload/cache paths under
  `/data/local/tmp/mbf`, Beat Saber app/OBB paths, and Scotland2's
  `/sdcard/ModData/<id>/Modloader` paths.
* **Version compatibility and verification:** exact version/resource status,
  CRC-checked inputs, manifest/resource cache, explicit downgrade graph and
  streamed structured status. Loader presence alone is insufficient.
* **Rollback:** staged errors and backups exist, but uninstall/reinstall is
  not an all-or-nothing transaction; player data is deliberately manual in
  one case. NFVR should adopt explicit partial-restore outcomes, not claim
  MBF has universal rollback.
* **Useful code paths / citations:** [`mbf-agent-wrapper.py`](https://github.com/Lauriethefish/ModsBeforeFriday/blob/4a953cb25546ee157db67a1b650c83f9c9afe402/mbf-agent-wrapper/mbf-agent-wrapper.py),
  [`patching.rs`](https://github.com/Lauriethefish/ModsBeforeFriday/blob/4a953cb25546ee157db67a1b650c83f9c9afe402/mbf-agent/src/patching.rs),
  [`downgrading.rs`](https://github.com/Lauriethefish/ModsBeforeFriday/blob/4a953cb25546ee157db67a1b650c83f9c9afe402/mbf-agent/src/downgrading.rs),
  [`request.rs`](https://github.com/Lauriethefish/ModsBeforeFriday/blob/4a953cb25546ee157db67a1b650c83f9c9afe402/mbf-agent/src/models/request.rs),
  [`response.rs`](https://github.com/Lauriethefish/ModsBeforeFriday/blob/4a953cb25546ee157db67a1b650c83f9c9afe402/mbf-agent/src/models/response.rs).
* **NFVR decision / confidence:** reimplement an NFVR-owned protocol/resource
  cache only after a licensing and provenance decision; copy architecture,
  not AGPL code or network services. **MAINTAINED_OPEN_SOURCE for Beat Saber,
  not a Gorilla capability.**

### MelonLoaderInstaller / LemonLoader

* **Repository / commit / license:** [MelonLoaderInstaller at
  `ac443fce...`](https://github.com/LemonLoader/MelonLoaderInstaller/tree/ac443fce9f2ef890caddf8c64bba9194a43361bd);
  GPL-3.0. README credits Apkifier (MIT), SAI (GPL-3), SplitAPKInstall
  (Apache-2), and QuestPatcher AXML/signing; those are separate obligations.
* **Maintenance, target, package, engine, ABI:** maintained Android/Quest
  installer, BONELAB-oriented but without an authoritative current BONELAB
  version matrix; Unity app detection uses `libunity.so`/`libil2cpp.so`;
  inspected repack path is arm64. Package identifiers are plugin/profile
  inputs, not proof of supported builds.
* **Format and loader:** base APK plus library/extra split APKs, binary AXML,
  assets, and arm64 native `.so`; LemonLoader/MelonLoader.
* **Preparation, APK patch, signing, reinstall:** detector and patcher copy
  every required split, resolve Unity dependencies, patch binary AXML,
  inject assets/native libraries, align/sign each APK with APK v2, then
  uninstall and install a package session containing the split set.
* **Data and OBB behavior:** OBB is moved/backed up and restored where
  possible. External/app data backup is offered, but arbitrary app data is
  deliberately not automatically restored because Android ownership/permission
  changes can break the app.
* **Destinations:** `assets/MelonLoader`, `assets/dotnet`,
  `assets/lemon_patch_date.txt`, and `lib/arm64-v8a/` in the base or
  library split as appropriate.
* **Version compatibility and verification:** Unity-version dependency
  extraction, arm64 detection, package-aware plugin compatibility, patched
  marker libraries, split enumeration, and install checks. No current
  BONELAB compatibility table was established.
* **Rollback:** explicit restore can reinstall saved APKs and restore OBB/data
  paths, but it is not a transaction and may require manual data recovery.
* **Useful code paths / citations:** [`Patcher.cs`](https://github.com/LemonLoader/MelonLoaderInstaller/blob/ac443fce9f2ef890caddf8c64bba9194a43361bd/Core/Patcher.cs),
  [`PatchManifest.cs`](https://github.com/LemonLoader/MelonLoaderInstaller/blob/ac443fce9f2ef890caddf8c64bba9194a43361bd/Core/PatchSteps/PatchManifest.cs),
  [`RepackAPK.cs`](https://github.com/LemonLoader/MelonLoaderInstaller/blob/ac443fce9f2ef890caddf8c64bba9194a43361bd/Core/PatchSteps/RepackAPK.cs),
  [`AlignSign.cs`](https://github.com/LemonLoader/MelonLoaderInstaller/blob/ac443fce9f2ef890caddf8c64bba9194a43361bd/Core/PatchSteps/AlignSign.cs),
  [`PatchRunner.cs`](https://github.com/LemonLoader/MelonLoaderInstaller/blob/ac443fce9f2ef890caddf8c64bba9194a43361bd/Core/PatchRunner.cs).
* **NFVR decision / confidence:** independently implement only reviewed
  behavior after a GPL/compliance decision and exact game/resource profile;
  do not claim general BONELAB preparation. **MAINTAINED_OPEN_SOURCE
  mechanism evidence, profile compatibility unverified.**

### LavaGang/MelonLoader

* **Repository / commit / license:** [MelonLoader at
  `982ed998...`](https://github.com/LavaGang/MelonLoader/tree/982ed9981ac5b0619648a2e79bcc02fd8861db33);
  Apache-2.0 in `LICENSE.md`.
* **Maintenance, target, package, engine, ABI:** maintained general Unity
  Mono/IL2CPP loader; Android/Oculus Quest support is explicitly marked WIP;
  no target package or production ABI matrix is established by this source.
* **Format and loader:** Unity managed/native runtime payloads; loader
  family is MelonLoader. It is not itself an APK patch/install workflow.
* **Preparation, APK patch, signing, reinstall:** a separate installer must
  perform all three; this repository does not establish those steps for a
  current Quest game.
* **Data/OBB, destinations, rollback:** no authoritative Android
  backup/restore or Quest destination contract was established.
* **Version compatibility and verification:** WIP platform status and loader
  runtime context only; require a game-specific profile and runtime probe.
* **Useful code paths / citations:** [README Android/Quest status](https://github.com/LavaGang/MelonLoader/blob/982ed9981ac5b0619648a2e79bcc02fd8861db33/README.md)
  and [`LICENSE.md`](https://github.com/LavaGang/MelonLoader/blob/982ed9981ac5b0619648a2e79bcc02fd8861db33/LICENSE.md).
* **NFVR decision / confidence:** do not enable production Android
  preparation from WIP text. **MAINTAINED_OPEN_SOURCE, WIP compatibility.**

### BoneLib

* **Repository / commit / license:** [BoneLib at
  `c6470de...`](https://github.com/yowchap/BoneLib/tree/c6470de53299b9d3adb6d1f62383177a812cbacd);
  GPL-3.0.
* **Maintenance, target, package, engine, ABI:** BONELAB managed-code
  library with Android/LemonLoader branches; package context is
  BONELAB (`com.StressLevelZero.BONELAB` in the ecosystem), but this is a
  runtime/library consumer, not an APK preparation engine. ABI is not a
  BoneLib installation field.
* **Format and loader:** managed C# library/content resources used by
  LemonLoader/MelonLoader; not an APK patch format.
* **Preparation, APK patch, signing, reinstall:** none supplied by BoneLib;
  these are required from a separately verified LemonLoader preparation
  strategy for code mods.
* **Data/OBB, destinations, version compatibility, rollback:** no APK/OBB
  transaction or universal version matrix; Android resource branches show
  runtime adaptation only. Code mods require compatible managed assemblies,
  loader, and game profile.
* **Useful code paths / citations:** [`HelperMethods.cs`](https://github.com/yowchap/BoneLib/blob/c6470de53299b9d3adb6d1f62383177a812cbacd/BoneLib/BoneLib/HelperMethods.cs),
  [`Notifications.cs`](https://github.com/yowchap/BoneLib/blob/c6470de53299b9d3adb6d1f62383177a812cbacd/BoneLib/BoneLib/Notifications.cs),
  [`MenuBootstrap.cs`](https://github.com/yowchap/BoneLib/blob/c6470de53299b9d3adb6d1f62383177a812cbacd/BoneLib/BoneMenu/MenuBootstrap.cs).
* **NFVR decision / confidence:** keep BONELAB content and code strategies
  separate; do not copy GPL library code into NFVR. **MAINTAINED_OPEN_SOURCE
  library evidence, not preparation evidence.**

### GorillaTag-Modding-Guide

* **Repository / commit / license:** [guide at
  `4180d19...`](https://github.com/burritosoftware/GorillaTag-Modding-Guide/tree/4180d19c45c06814374cc9737e671e785d805395);
  ISC.
* **Maintenance, target, package, engine, ABI:** community Quest and PC
  instructions; Quest references Gorilla Tag's
  `com.AnotherAxiom.GorillaTag`, Unity IL2CPP historically; PC instructions
  are Windows/Mono/BepInEx. No current Quest APK, ABI, or package-version
  evidence.
* **Format and loader:** historical QuestPatcher/QMOD workflow; PC BepInEx
  DLL workflow; custom maps are a separate content flow.
* **Preparation, APK patch, signing, reinstall:** Quest instructions say
  patch/reinstall after game updates but do not provide implementation or
  signing algorithms. PC has no APK operations.
* **Data/OBB, destinations, version compatibility, rollback:** no
  authoritative transaction or current version matrix; update guidance
  warns that mods must be checked after game updates.
* **Useful code paths / citations:** [Quest beginners guide](https://github.com/burritosoftware/GorillaTag-Modding-Guide/blob/4180d19c45c06814374cc9737e671e785d805395/docs/user-guide/quest/beginners-guide.md),
  [Quest update guide](https://github.com/burritosoftware/GorillaTag-Modding-Guide/blob/4180d19c45c06814374cc9737e671e785d805395/docs/user-guide/quest/quest-updating.md),
  [PC beginners guide](https://github.com/burritosoftware/GorillaTag-Modding-Guide/blob/4180d19c45c06814374cc9737e671e785d805395/docs/user-guide/pc/beginners-guide.md).
* **NFVR decision / confidence:** preserve Quest/PC/content separation and
  use only as historical workflow context. **HISTORICAL_REFERENCE; no current
  Gorilla capability.**

### QuestPatcherWORKING: source-diff and trust result

* **Repository / commit / license:** [fork at
  `5365fe2...`](https://github.com/QuestModsDev/QuestPatcherWORKING/tree/5365fe24a5894ce7d47ff21b293db85599de9500);
  no checked-in governing license.
* **Target, engine, ABI, format, loader:** claims current Gorilla Tag Quest
  (Unity IL2CPP), but retains the upstream APK/AXML/ABI/QMOD model and does
  not add a verified game-specific resource or ABI rule.
* **Exact source diff:** complete static comparison found only:
  `LibMainLoader` URL upstream `v0.2.0` -> fork `v0.1.0-alpha`;
  Scotland2 URL upstream `v0.1.7` -> fork `v0.1.4`; README wording/image;
  fork-only image and upstream-only metadata/license files. No
  `PatchingManager`, AXML, IL2CPP, ABI, or Gorilla-specific patch-code
  change was found.
* **Preparation, patch/sign/reinstall, data/OBB, destinations,
  compatibility, verification, rollback:** inherited upstream behavior in
  source; no new verified contract. README claim “all current” does not
  provide APK hashes, version table, resource proof, or runtime verification.
* **Useful code paths / citations:** the only implementation delta is
  [`file-downloads.json`](https://github.com/QuestModsDev/QuestPatcherWORKING/blob/5365fe24a5894ce7d47ff21b293db85599de9500/QuestPatcher.Core/Resources/file-downloads.json);
  the claim is in the fork [`README.md`](https://github.com/QuestModsDev/QuestPatcherWORKING/blob/5365fe24a5894ce7d47ff21b293db85599de9500/README.md).
* **NFVR decision / confidence:** do not use its release software, bundled
  menus, or unknown binaries. The backwards resource changes cannot enable
  production Gorilla preparation. **COMMUNITY_EXPERIMENTAL / UNTRUSTED.**

### Malachi Mod-Manager: static binary findings

* **Repository / commit / license:** [repository at
  `2ef1ae2...`](https://github.com/Malachi-the-modder/Mod-Manager/tree/2ef1ae2fe250a94f59bae0f93825b5ab1e21aa26)
  and [tagged release](https://github.com/Malachi-the-modder/Mod-Manager/releases/tag/Mod_Manager);
  no checked-in license. The inspected release `ModManager.exe` was PE32+
  x86-64, unsigned in the PE security directory, with a developer PDB path;
  its SHA-256 was `58495d1687a82a7424ddf20ab9f2c44308128eabb63b7e3cfdefe7fd89b36264`
  **only as a static inspection identifier**, not an NFVR asset.
* **Maintenance, target, package, engine, ABI:** Windows PC Gorilla Tag,
  Mono/.NET/BepInEx; no Android package ID, APK, AXML, ARM ELF, or Quest
  ABI.
* **Format and loader:** Windows PE/.NET manager; `BepInExFiles.zip`
  contains Windows `winhttp.dll`, `doorstop_config.ini`, managed BepInEx
  assemblies, and plugin DLLs.
* **Preparation, APK patch, signing, reinstall:** none; no Android
  preparation or signing.
* **Data/OBB, destinations, version compatibility, verification, rollback:**
  PC game root (`Gorilla Tag.exe`), `BepInEx/`, and
  `BepInEx/plugins`; text/catalog retrieval and Windows path checks are
  PC-only. No Quest OBB or APK rollback contract.
* **Useful static evidence:** `ModManager.exe`, `BepInExFiles.zip`,
  `GorillaComputer.zip`, `UnityExplorer.zip`, and the standalone DLLs listed
  in the tagged release; PE headers, archive listings, strings, Windows paths,
  and the absence of Android/ARM/APK artifacts establish the platform
  finding. The release is linked above; no artifact is retained here.
* **NFVR decision / confidence:** reject artifacts and behavior, including
  anti-detection, Defender exclusions, cheat/menu, ban/crash, or bypass
  functionality. Only the generic idea of a catalog/provenance record is
  transferable. **UNTRUSTED_BINARY; high-confidence PC-vs-Quest finding.**

### GT_CustomMapExample: built-in content

* **Repository / commit / license:** [example at
  `02bac82...`](https://github.com/Another-Axiom/GT_CustomMapExample/tree/02bac82f9d6a6f79baf7f4c73e01ed3e4d2b3c56);
  CC BY-NC-SA 4.0.
* **Maintenance, target, package, engine, ABI:** Gorilla Tag Virtual
  Stump/custom-map Unity content; no executable loader, package ABI, or
  current APK matrix.
* **Format and loader:** `.gtmap`/export ZIP and Luau/content assets for
  built-in game navigation; no QMOD/native loader.
* **Preparation, APK patch, signing, reinstall:** none established for
  content delivery; no reason to patch an APK merely because a map is
  selected.
* **Data/OBB, destinations, version compatibility, verification, rollback:**
  use an authoritative content-layout profile and content hash/structure
  checks; do not infer code-loader readiness or implement APK rollback for a
  content-only package.
* **Useful citation:** [project README](https://github.com/Another-Axiom/GT_CustomMapExample/blob/02bac82f9d6a6f79baf7f4c73e01ed3e4d2b3c56/README.md).
* **NFVR decision / confidence:** classify as `BUILT_IN_GAME_CONTENT`,
  separate from QMOD/native code and PC BepInEx. **HISTORICAL_REFERENCE /
  content-format evidence.**

## QMOD and APK algorithm findings

### QMOD validation contract

The specification requires a valid PKWARE ZIP, no ZIP64, exactly one
full-name `mod.json`, UTF-8 JSON without BOM, and schema-valid identity. The
model requires `id`, `name`, `author`, and semantic `version`; `id` cannot
contain whitespace. `packageId`, `packageVersion`, `modloader`, file lists,
dependencies, and explicit copies are separate concerns. Required
dependencies must be installed and in range. Optional dependencies may be
absent, but a present optional dependency must satisfy its range.

The inspected .NET parser does not itself enforce every ZIP-level
requirement (notably ZIP64 and all unsafe path/link cases). NFVR intake must
therefore reject duplicate/aliased manifests, ZIP64, absolute paths, drive
paths, traversal, unsafe links, excessive expansion, and destination
collisions before extraction. `downloadIfMissing` is manifest metadata, not
permission to download arbitrary code.

### APK preparation sequence found in source

Across QuestPatcher, MBF, and MelonLoaderInstaller, the common sequence is:

1. Bind the selected device/package; inspect package paths, version, splits,
   Unity/IL2CPP and ABI.
2. Copy/pull APKs to a temporary workspace and hash originals. Back up OBB
   and assess what Android data can be preserved.
3. Patch binary AXML rather than treating `AndroidManifest.xml` as text.
   Add only expected permissions/flags and native/assets for the selected
   profile and preserve base/split topology.
4. Inject ABI-correct native libraries and any exact Unity/resource files.
5. Align/sign the copied APK set using controlled, pinned tooling.
6. Recheck serial, package, version, split set, and original hashes
   immediately before uninstall.
7. Uninstall/install the coherent package set, restore only verified OBB/data,
   recreate loader destinations, and verify package/version/loader markers.
8. Report partial restore or uncertain runtime state explicitly. Keep
   immutable originals and rollback metadata; do not claim a complete
   transaction where Android constraints prevent one.

## Broad search results

Additional GitHub discovery covered: “Quest mod loader”, “Meta Quest mod
manager”, “Oculus Quest IL2CPP patcher”, “Android IL2CPP modloader”, “Gorilla
Tag Quest mod”, `com.AnotherAxiom.GorillaTag`, “QuestLoader Gorilla Tag”,
“Gorilla Tag qmod”, “Gorilla Tag patch APK”, “Beat Saber Quest mod patch”,
“BONELAB Quest mod loader”, “LemonLoader APK patch”, “Quest APK modloader”,
“QMOD installer”, “LibMainLoader”, “QuestPackageManager”, and “Android Unity
mod manager”.

Relevant additional findings (not promoted to automatic support):

| Repository / inspected commit | Finding and decision |
|---|---|
| [beatsaber-hook](https://github.com/sc2ad/beatsaber-hook/tree/218f2fd89a87b2787ac2302ed1278b62eb4f78c5), `218f2fd89a87b2787ac2302ed1278b62eb4f78c5` | MIT/Beat Saber hook context; game-specific reference only |
| [Goldybin/BeatOn](https://github.com/Goldybin/BeatOn/tree/a23d93635701b5c2457a7e96c9f1800b6cdf6d6c), `a23d93635701b5c2457a7e96c9f1800b6cdf6d6c` | Older Quest/Beat Saber reference; no current NFVR profile |
| [RedBrumbler/QuestAppPatcher](https://github.com/RedBrumbler/QuestAppPatcher/tree/ebd115335c8ac7ff18bb195976a5898eb1155708), `ebd115335c8ac7ff18bb195976a5898eb1155708` | Archived GPL-3.0 historical reference; no production enablement |
| [NonLogs/QuestPatcher](https://github.com/NonLogs/QuestPatcher/tree/9b5f4211f41423ce326819120a98638a616a54e0), `9b5f4211f41423ce326819120a98638a616a54e0` | zlib historical fork/reference; no current version proof |
| [RockThePandora/MonkePatcher](https://github.com/RockThePandora/MonkePatcher/tree/d23621c892d820f3b43e2f7c794399a62f17d7f2), `d23621c892d820f3b43e2f7c794399a62f17d7f2` | Historical/community Gorilla reference; not an authority for current APKs |
| [M4LivesAgain/gorilla-tag-quest-modding](https://github.com/M4LivesAgain/gorilla-tag-quest-modding/tree/a1f75c2bdc80e5d488e323e6554fb0bf9ad92f0a), `a1f75c2bdc80e5d488e323e6554fb0bf9ad92f0a` | Community Gorilla material without sufficient source/version/license evidence; no automatic behavior |

Search results also included binary collections, old experiments, and
repositories advertising cheats, anti-detection, moderation bypass, crash/ban
tools, or credential/token behavior. Those were not imported, executed, or
used as compatibility evidence.

## Licensing and source-compliance decision

This research changes documentation only. No upstream source code, release
binary, APK, native library, agent, map asset, or mod package is imported
into NFVR by this report. The recommended implementation is an independent
implementation of documented mechanisms, not a copied implementation.

If a later change adapts permissively licensed source, preserve the exact
notice and record repository, commit, files, and modifications:

* QuestPatcher/QMOD use the checked-in custom three-clause notice; retain it
  and mark altered source.
* QuestLoader and Scotland2 require MIT notices.
* MelonLoader is Apache-2.0 and requires its notice/conditions.
* GorillaTag-Modding-Guide is ISC; GT_CustomMapExample is CC BY-NC-SA 4.0
  and its content license is not a general engine license.

MBF (AGPL-3.0), MelonLoaderInstaller (GPL-3.0), and BoneLib (GPL-3.0) are
architecture/reference sources here. Their code is not copied or adapted
into the NFVR client. Any future decision to use those components must
explicitly handle source/distribution obligations and their separately
credited dependencies. Native release artifacts remain separately reviewable
even when their source repository is permissively licensed. Unknown or
unlicensed community binaries remain excluded.

## Architecture decision input for NFVR

The evidence supports two independently testable interfaces:

### Game preparation / loader layer

A data-driven profile should include package ID, Unity engine (Mono or
IL2CPP), ABI, version name/code allowlist or range with exact resource
hashes, split/OBB policy, loader family, required AXML/native changes,
source URL/commit/license, evidence rank, verification probes, and rollback
policy. States should distinguish `STOCK_GAME`, `PATCH_REQUIRED`, `PATCHING`,
`PATCHED`, `QUESTLOADER_READY`, `SCOTLAND2_READY`,
`LEMONLOADER_READY`, and `PREPARATION_FAILED`.

The common foundation should own serial/package/version capture, APK/split
inspection, immutable SHA-256 backups, temporary workspaces, ZIP/AXML
validation, ABI-specific injection, controlled signing, stale-state
rechecks, coherent reinstall, OBB/data restoration assessment, loader
verification, and explicit rollback metadata. A failed backup, changed serial,
changed package/version, changed original hash, unknown profile, or missing
resource must stop before uninstall.

### Mod-package layer

Strategies should be separate: `QMOD`, `BONELAB_CONTENT`,
`BONELAB_CODE`, `ANDROID_DATA_LAYOUT`, `ANDROID_OBB_LAYOUT`,
`RAW_LOADER_MOD`, and `BUILT_IN_GAME_CONTENT`. A package requiring code
loading cannot execute until its preparation state is verified. A `.gtmap`,
pallet, or other direct content package must not trigger APK patching.

## Actual NFVR capability boundary

This report establishes **no new NFVR preparation, patch, signing, reinstall,
loader, rollback, or mod-install capability**. It records source algorithms
and the evidence needed before architecture/code work. In particular, this
research does not verify any installed version, APK hash, ABI, split set,
device, loader runtime, QMOD installation, BONELAB code path, Beat Saber
profile, or Gorilla Tag preparation.

The architecture decision should consequently treat the following as
research conclusions, not completed product features:

* Generic APK copy/AXML/native injection/sign/reinstall is a demonstrated
  upstream pattern, but NFVR has not thereby implemented it.
* QMOD fields and loader-relative destinations are understood, but parsing
  or installation is not thereby enabled for an unprepared game.
* MBF demonstrates version-bound Beat Saber resource/agent architecture, not
  a general NFVR service or Gorilla support.
* LemonLoaderInstaller demonstrates split-aware Android mechanics, not a
  current BONELAB compatibility matrix.
* The Gorilla Tag package identifier is known, but current preparation is
  unverified until an exact profile and device/APK evidence are supplied.
* PC BepInEx artifacts and built-in custom-map content remain separate from
  Quest executable code mods.

## Later acceptance evidence required

When implementation is separately authorized, a real-device run should record:
selected ADB serial; package/version/versionCode; base and split APK paths and
SHA-256; ABI and Unity/IL2CPP evidence; OBB/data inventory and backup outcome;
profile source/commit/license/resource hashes; AXML/native changes; signature
verification; serial/version/hash recheck immediately before uninstall;
coherent install result; restored paths and hashes; loader runtime probe;
QMOD package/dependency/destination checks; and any rollback or partial-restore
outcome. Analysis success, preparation success, and mod-install success must
be separate results. No source-only claim can substitute for that evidence.

## Implementation-pass addendum

The preceding sections were written before the architecture decision. The
same authorized pass subsequently implemented the limited scope below. This
addendum supersedes the research-only implementation status, not the source
evidence or compatibility limits.

### Implemented and reachable

* Separate preparation assessment and package-installation strategy contracts.
  Preparation profiles require trusted evidence rank, one exact version-name /
  version-code pair, ABI, engine and resource-hash evidence. The shipped profile
  registry is empty: no invented compatibility entries.
* Mods-only readiness inspection and local APK backup actions. The engine
  inventories base and splits through ADB, checks device authorization,
  package/version/base-path identity, and hashes pulled bytes. Backup creation
  rechecks the inventory before and after the operation and requires the
  actual copied bytes to match the captured hashes.
* APK ZIP/path/size/entry-count inspection, full ELF magic/class/endianness/
  version/machine checks, and Unity/IL2CPP evidence. Inspection evidence is
  bound to source paths and SHA-256, not merely a list position.
* Integrity-checked, separate local APK copies with rollback **metadata only**.
  Metadata rejects out-of-root paths, symlinks, empty or duplicate inventories,
  invalid hashes, altered identity, changed APK sets and rollback capability
  claims. Invalid metadata is never used to construct inspection inputs.
  Read-only file permissions are defense in depth, not an immutability claim.
* Shared operation exclusion and generation/device/app freshness checks.
  Preparation cannot overlap mod analysis/installation or publish a stale
  selected-app result.
* Eight overall Mods stages, Arabic customer-facing readiness/status copy,
  opt-in technical diagnostics, explicit no-patching and APK-only backup
  notices. Analysis, local backup, preparation and verified installation remain
  distinct. Direct-content installation is not forced through APK preparation.
* Stronger QMOD schema/type/package/version checks, raw manifest-name and
  traversal validation, bounded ZIP64/symlink handling, loader requirements
  for Gorilla loader-directory copies, and safe multiple-destination copies.
  Optional dependencies remain blocked where installed dependency presence/
  version cannot be established; copyExtensions remains an explicit unsupported
  registration/import prerequisite rather than being reinterpreted.

### Not implemented or verified

There is **no automatic preparation ecosystem enabled**. There is no NFVR
AXML mutation, native-loader injection, APK signing, patched APK reinstall,
OBB/app-data backup or restoration, executable rollback, downloaded patch
resource provider, or deployed on-device native agent in this pass.
The strategy interfaces and assessment records are not a complete executable
patch pipeline. No physical Windows/Quest journey or actual game version was
verified. Generic upstream patch algorithms do not change these facts.

Existing mapped QMOD installation on a suitably prepared compatible app,
NFVR manifests, and supported direct-content/data layouts remain available
under their existing archive, package, loader, identity and verification
guards. No new BONELAB code-loader preparation or current Gorilla/Beat Saber
version support is claimed. Virtual Stump remains informational built-in
content. PC DLL/BepInEx assets remain outside Quest installation.

Version remains **2.3.3** because the brief makes 2.4.0 conditional on substantial
working patch/loader functionality. Upgrade UUID and product identifier are
unchanged. No upstream code or downloaded binary was imported; implementation
uses the project's existing dependencies and independently written Kotlin.

### Validation

JDK 17: compileKotlin, test, check and build passed. The final full suite has
**177 tests, zero failures, zero errors, zero skipped**. Existing tests were
retained. Added adversarial coverage includes source/copy hash mismatches,
metadata traversal and rollback-claim tampering, wrong-source inspection
evidence, stale APK/version and split identity, malformed ELF, profile
cross-product rejection, QMOD loader/schema/dependency/archive cases and
Arabic/eight-stage UI semantics. This is Linux/JVM verification, not physical
Windows/Quest acceptance or a patched-game compatibility test.

### Windows + Quest acceptance procedure for this subset

1. Run the client on Windows and authorize the intended Quest. Record package,
   versionName/versionCode and installed base/split inventory.
2. In Mods, select the game and invoke read-only APK readiness inspection.
   Confirm the displayed identity and that missing exact profile evidence is
   explicit. No patch/sign/reinstall action should become enabled.
3. Request local APK backup. Confirm hashes and base/split count, and that the
   UI explicitly excludes OBB/app data and executable rollback.
4. Recheck a valid backup; then alter a copied APK or metadata in an isolated
   test backup and confirm it is rejected, never used as inspection evidence.
5. Disconnect/change the selected device or app during inspection and confirm
   results are discarded. A same-version replaced APK must invalidate backup
   matching because its bytes/path-set changed.
6. Select Gorilla Virtual Stump content: no manual installation or patching
   success. Select a stock-game QMOD: explicit loader/preparation requirement.
7. With an independently prepared test app, analyze only a matching QMOD whose
   required loader is detected. Review destinations; reject wrong package,
   version, loader or unresolved dependencies.
8. Install a supported direct-content package on a test device. Confirm only
   remote file verification can complete the install/verification stages.
9. Confirm Install Games, PCVR Readiness and licensing behave independently.
   Record failures and diagnostics; do not call a successful local backup a
   successful patch, game launch, mod installation or rollback.