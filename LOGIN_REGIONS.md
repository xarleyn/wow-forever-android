# Account regions, official Battle.net and login diagnostics

The launcher supports named Battle.net launch profiles with an explicit WoW account region:
EU (Europe), US (Americas), KR (Korea) or TW (Taiwan). Profiles can share the same email,
so one Battle.net login can have both a `WoW1 (EU)` and a `WoW1 (US)` entry.
Profile names are local labels. The launcher does not enumerate or select server-issued
WoW subscription IDs; choose a subscription in the game if it asks.

## Configuration and migration

- Add/select/edit/remove profiles in the **BATTLE.NET ACCOUNT** card on the Status tab.
- Passwords use the existing Android Keystore `Crypto` implementation. Passwords are never
  included in account metadata or generated `toString()` output.
- A legacy saved login is migrated without decrypting or replacing its ciphertext.
  Its region remains unconfirmed until the user explicitly selects one in **Edit**.
  Auto-launch waits for this confirmation. Region is not inferred from email, locale or CDN branch.
- `wowf` uses `portal "EU"`, `"US"`, `"KR"` or `"TW"` from the selected profile.
  `wow_classic_beta` retains its known `portal "test"` independently of that profile's region.
- Selecting/editing a profile updates `portal` and `agentUID` in an existing `Config.wtf`.
  Before every launch, configuration is checked again. A missing config gets the normal first-run
  handheld defaults. Config writes use a temporary file and an atomic replacement where supported.
- Other settings, comments and `WTF/Account/` directories are preserved. The existing renderer
  and resolution rules still apply during launch. Duplicate managed keys are collapsed.
- Removing the active profile does not silently activate another one. Credentials must be
  selected again before launching. Switching/removing profiles clears stale `login.txt` files
  for both supported products.
- `login.txt` is written only after downloads/container preparation succeed. It is a plaintext
  file required by the existing game login path; the watcher clears it after game log activity
  or a three-minute timeout, and the existing return/exit hooks also clear it. This does not
  provide token authentication or bypass Blizzard's verification.

## Evidence and limits

A local PC reference install was checked without modifying it:
its `_forever_/WTF/Config.wtf` contains `portal "EU"` and
`agentUID "wowf"`; its `Connection.log` contains `launcherPortal="eu.actual.battle.net"`,
`loginPortal="eu.actual.battle.net:1119"`, `usedToken="true"` and `ERROR_OK (0)`.
The launcher previously forced `portal "test"` for both products.

This proves a configuration mismatch, but does not by itself prove that an unknown portal
causes the observed China label, or that changing it fixes Android authentication. The PC used
a token while the Android `login.txt` path uses password login. Do not treat those as equivalent.
[Upstream issue #11](https://github.com/xarleyn/wow-forever-android/issues/11) also reports
14400 with a matching beta portal and `usedToken="false"`; its server-verification explanation
is a community hypothesis, not an established protocol contract.

## Diagnostics and device acceptance

The **Logs** tab now starts with the selected product's `Logs/Connection.log` tail,
bounded to 128 KiB and 400 lines. It retains routing, token-use flags and response codes;
other quoted connection fields and common authentication/email fields are redacted.
The same text is used for display, Copy and Share. These logs can contain older launches:
compare the latest timestamps, not just any occurrence of a hostname or error.

On an attached device, verify both EU and US using actual accounts:

1. Cancel auto-launch with Back to reach Status. Confirm the migrated profile's region.
2. Add a second profile, switch EU â†’ US â†’ EU, and inspect the existing selected product's
   `WTF/Config.wtf`. Its portal must follow the profile without losing custom settings.
3. Launch and inspect the region label and the latest `launcherPortal` / `loginPortal` in Logs.
   EU should be checked against the PC reference `eu.actual.battle.net`.
4. Confirm authentication reaches the character list. A launch to the login screen alone is
   insufficient. If 14400 remains with a correct portal, inspect the latest `usedToken` and
   server response before deciding on a separate authentication change.
5. For beta, confirm `portal "test"` is retained. For both products, verify profile edits,
   deletion, keyboard input and narrow-screen layout on the physical device.

## Official Battle.net mode

Select **Battle.net** in the **LAUNCH WITH** card to install/open the official Windows
launcher in the dedicated `wow_battlenet` container. Existing installations retain direct
launch until this mode is explicitly selected. Sign-in and any verification happen in
the official client. Saved credentials from direct mode are not supplied to Battle.net.

Game files remain disconnected initially. Enabling game access mounts the selected WoW
folder as `G:`; the official client can then manage that folder. The app itself does not
copy a PC account database, import authentication state or generate launch tokens.
The normal downloader supplies an ARM64 game client; it does not automatically generate
Battle.net installation metadata. Merely mounting a copied folder does not prove adoption.

The runtime preparation:

- Downloads the official installer over HTTPS from Battle.net and caches it locally.
- Verifies the checksum-pinned FEX 2609 package and repairs damaged installed copies.
- Inventories static/delay PE imports and prepares matching Wine DLLs, including the
  common-controls v6 WinSxS assembly and managed uppercase aliases on Android filesystems.
  Existing valid native overrides are preserved.
- Sets only `Client.HardwareAcceleration="false"` to avoid the observed blank Chromium
  window. Existing account, cache and AutoLogin settings remain client-owned.
- Runs a small source-built Win32 loader probe before normal launch. Its output and wait
  are bounded; optional diagnostic failure does not cancel normal client startup.
- Shows **Keyboard** and **Controls** buttons while Battle.net is open. Gamepad controls
  start hidden and can be enabled when needed.

The probe tests real COMCTL32 loads under activation contexts and uses a fixed, non-secret
DPAPI canary to check encryption across launches. It never reads client credentials,
cookies or authentication tokens. Sources are `tools/battlenet_probe.*`; build the bundled
EXE with `tools/build_battlenet_probe.ps1` using Visual C++ x86 build tools and Windows SDK.
No Blizzard executable is bundled in this repository.

### Device findings, 2026-10-23

On a physical ARM64 Android device, EU routing and public HTTP/HTTPS requests through
WinHTTP inside the application's Wine environment succeeded. A direct password attempt
still returned 14400 with `usedToken=false`. The game displayed **China Release arm64**
even while connected to the EU endpoint, so that label is not reliable routing evidence.
ADB `run-as` socket failures were traced to a different SELinux domain and did not prove
an application networking failure.

After disabling browser hardware acceleration, the official Battle.net main window
rendered after user sign-in. Exiting and restarting retained the session; the native
DPAPI previous-launch check also passed. This verifies those restarts, not indefinite
session lifetime or exemption from later account challenges.

A separate, same-build import experiment copied only game installation metadata and
missing bootstrap executables from a PC reference. After backing up and merging only
the game's installation record, Agent reported `installed=true`, path `G:` and build
70532. Battle.net displayed **You have pre-installed this game**, version 1.60.1.70532,
and an **Update** button. This import was a diagnostic operation, not an automatic feature
of the app. Account/session data and existing game settings were preserved.

Agent subsequently marked the build non-playable with **Permissions check failure (2123)**.
A later user-triggered update identified a filesystem failure: **Unable to create TACT
container folder 'G:/Data/'**, preceded by a warning about a symlink at `G:`. This is
not evidence of an account-entitlement or closed-test rejection. The client separately
displayed a future availability date and an expired subscription. Recognition of the
installed game is verified; an in-game character-list launch is still unverified.
No update, repair or bulk game download was started during the initial adoption check.

A scan-time Android crash exposed concurrent removal from the shared-memory map while
X11 detached a buffer. All map access now uses the same monitor. The updated phone
completed another scan without that crash; an emulator concurrency test also passes.

## Updating a shared installation

Both launch modes currently use the same selected game folder. Separate Wine containers
do not isolate `Data`, `.build.info`, game executables or overlapping DLLs in that folder.

The direct-mode updater explicitly selects Windows ARM64 manifest entries and excludes
x86_64. The imported PC installation is tagged Windows x86_64; Battle.net has not been
validated to keep the ARM64 executable and its dependencies in sync while updating the
shared CASC data. A Battle.net update/repair can therefore leave incompatible versions
for direct launch even if `Wow-ARM64.exe` remains present.

Until that update path is validated, use the direct-mode updater for the shared ARM64
installation. Avoid alternating update/repair tools on it. Test Battle.net patching on
a separate installation, or first configure and verify its ARM64 selection, then verify
both the installed manifest/binary versions and an actual direct-mode game launch.
Neither an Update button nor a successful patch alone establishes cross-mode compatibility.

The 2026-10-23 CDN check still reported wowf build 1.60.1.70532 with the same build-config
key as the phone, but a different CDN-config key. An Update button did not establish a
new client build in that case. The original ARM64 executable still matched the reference
after the failed Agent update attempts.

## Log export

Small Share payloads remain inline. Logs exceeding 4,000 characters or exceeding 100 lines
are shared as UTF-8 `.txt` attachments through FileProvider with temporary read access.
Each share is a separate snapshot; later refreshes/shares cannot overwrite an attachment
already handed to another app. Copy remains text-based. Common account/authentication
fields and email addresses are redacted before either kind of Share payload is created.

Battle.net diagnostics include bounded loader/runtime output, dependency inventories,
config booleans and Chromium error categories/codes. Full client config, cookie contents
and arbitrary browser console messages are not exported. Review any log before sharing it.
Raw investigation logs, screenshots, device identifiers and local databases stay outside
version control.

## Local verification

Use JDK 21, the configured Android SDK, and the four ignored bundled runtime archives:

```powershell
.\gradlew.bat testModernDebugUnitTest --tests 'app.gamenative.ui.screen.wow.*' assembleModernDebug
```

Tests cover first-run and existing configs, all four regions, beta's test portal, duplicate
keys, preserved settings/account files, migration, profiles sharing an email, persisted selection,
failed encryption/decryption, deletion without a silent account switch, input-line injection,
and bounded/redacted connection diagnostics. Android Keystore itself requires device validation;
storage unit tests inject a cipher so they can verify failure handling independently.

Focused host checks cover region/profile behavior, launcher selection, FEX integrity,
PE dependency staging, browser configuration preservation, safe diagnostics and bounded
process capture. Device tests separately cover file-based sharing, loader preparation,
FEX repair, shared-memory concurrency and toolbar touch targets.

`WineNetworkProbeTest` is opt-in: it requires a captured environment in
`files/tmp/network-probe-env.json` and restarts the target app. Run it only when interrupting
the current game is acceptable. It sends public HEAD requests and keeps TLS verification.
Do not commit a captured process environment or client/session files.

`tools/start_test_emulator.ps1` starts a disposable emulator and reports the explicit ADB
serial to use. The x86 emulator can validate isolated Android components, but its native
ARM64/Vulkan bridge did not run the complete Wine client successfully. Do not treat those
component tests as physical-device gameplay acceptance.

All four bundled runtime assets can be verified against `tools/components.sha256`.
