# Moto Clone Allowlist

An Xposed module (LSPosed / Vector) that unlocks Motorola's **built-in app cloner** for every app.

Stock Motorola firmware only lets you clone a few messaging apps in
**Settings → Apps → Cloned apps**. If you clone anything else from adb
(`pm install-existing --user 10 <pkg>`), the clone runs but the Moto launcher hides its icon.
This module fixes both:

- **Settings → Cloned apps** lists every user-installed app that has a launcher icon, each with a **+** button.
- The **Moto launcher** shows clone-badged icons for those apps in the clone profile, so you can put them on the home screen.

No adb is needed after setup.

## How it works

Moto ships a static resource overlay (`/product/overlay/MotoFrameworkResAppcloneOverlay.apk`) that sets the framework array
`android:array/cloneable_apps` to eight messengers: Messenger, Skype, WeChat, Telegram, Viber, WhatsApp, LINE and KakaoTalk.
Two apps read that array with `Resources.getStringArray()`:

| App | What it uses the list for |
|---|---|
| Settings (`com.android.settings`) | `ClonedAppsPreferenceController` / `AppStateClonedAppsBridge`: which apps can be cloned |
| Moto launcher (`com.motorola.launcher3`) | `Utilities.getSupportedCloneApps()`: which clone-profile apps get an icon |

`system_server` never reads it. The module hooks `Resources.getStringArray()` in **only those two processes**. When the
`cloneable_apps` array is requested, it appends every launchable, user-installed (non-system) package to Moto's
original list. Preinstalled system apps are skipped on purpose. Android copies many of them (Camera, Moto, Google
apps...) into every clone profile automatically. Listing them would make them appear in the launcher as clones that
Settings can't remove. The resource
ID is looked up by name at runtime, and the result is cached for 15 seconds so new installs appear quickly.

Because it never runs in `system_server`, a failure can't crash the system or trip Zygisk's crash protection.
If anything goes wrong, Settings and the launcher fall back to Moto's original list.

Clones go into Moto's single clone profile (user 10), so you get **one clone per app**, the same as the stock feature.

The Moto launcher always shows a cloned **Play Store** once the clone profile exists. That's stock behavior
(`LoaderTask.loadAllApps` special-cases it), and cloned apps need it for purchases and updates.

### Cleaning up unwanted clones

If system apps ended up in the clone profile (e.g. from an older version or from adb experiments), remove them per user:

```bash
adb shell pm uninstall --user 10 com.motorola.camera5   # reversible: pm install-existing --user 10 <pkg>
```

## Requirements

- A Motorola device with the stock "Cloned apps" feature, on Android 14 or newer
- Root, with Zygisk (e.g. KernelSU-Next + Zygisk Next) and an Xposed framework (Vector / LSPosed, legacy API 82+)

Tested on a Motorola Razr Fold 2026 (XT2651-1, Android 16), KernelSU-Next 3.4.2, Zygisk Next 1.5.3 and Vector 2.0 (3021).

## Install

1. Download `moto-cloner-allowlist.apk` from [Releases](../../releases) and install it.
   Use `adb install --user 0 moto-cloner-allowlist.apk` so it isn't copied into clone or work profiles.
2. In your Xposed manager, enable **Moto Cloner Allowlist**. The scope is preset to **Settings** and **Launcher3QuickStep**
   (`com.android.settings`, `com.motorola.launcher3`).
3. Force-stop Settings and the launcher, or just reboot.
4. Open **Settings → Apps → Cloned apps** and tap **+** next to any app.

## Build

No Gradle. You need a JDK (17+) and the Android SDK with `platforms/android-36` and `build-tools/37.0.0`.

```bash
./build.sh
```

The script compiles against minimal Xposed API stubs in `stubs/` (compile-time only; the framework provides the real
classes), dexes with `d8`, links with `aapt2`, then aligns and signs. The first build creates `signing.keystore`.
Keep it so later builds install as updates. It is git-ignored.

## Note: AppClone-style "raise user limit" modules on Motorola

Modules that hook `UserManager.getMaxSupportedUsers()` to return a huge value (e.g. 21472) **boot-loop Motorola
devices**. `MotorolaSettingsProvider` allocates a `MemoryIntArray` of `maxUsers * 2 + 13` entries, which is capped at
1024, so `system_server` crashes with `IllegalArgumentException: Max size is 1024`, and Zygisk Next eventually disables
itself ("Stop zygisk due to crash"). Any value up to 505 is safe. This module doesn't touch user limits.

## License

MIT
