# Account regions and BLZ51914400

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
2. Add a second profile, switch EU → US → EU, and inspect the existing selected product's
   `WTF/Config.wtf`. Its portal must follow the profile without losing custom settings.
3. Launch and inspect the region label and the latest `launcherPortal` / `loginPortal` in Logs.
   EU should be checked against the PC reference `eu.actual.battle.net`.
4. Confirm authentication reaches the character list. A launch to the login screen alone is
   insufficient. If 14400 remains with a correct portal, inspect the latest `usedToken` and
   server response before deciding on a separate authentication change.
5. For beta, confirm `portal "test"` is retained. For both products, verify profile edits,
   deletion, keyboard input and narrow-screen layout on the physical device.

Community users describe installing the official Battle.net Windows launcher inside a Wine
environment as a possible fallback. This fork does not yet implement that mode. It needs its
own installer/executable selection, architecture/runtime validation and real token-login test;
do not reuse a PC token or claim that writing `login.txt` supplies one.

ADB reported no attached devices during this implementation. Physical-device layout,
region-label/portal resolution, successful Android login and the Battle.net launcher fallback
remain unverified. The existing installed app and game files were not replaced or removed.

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

The final host suite has **28 passing tests, 0 failures, 0 skipped**. It includes a Compose
interaction test for selecting the US profile on a 360 dp screen.
The migrated-login dialog test is in `app/src/androidTest/` and its Kotlin compilation passes.
It was moved to device instrumentation because this project's Robolectric/Compose combination
does not reach idle while the platform dialog is open. That interaction has not been executed
on a device; do not count it as a passing host test.

All four bundled runtime assets were read back and SHA-256 checked against
`tools/components.sha256`.
