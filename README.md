# Ultimate Debugging Tool

An Android app that turns one phone into a service workstation for
another. Connect the phone to be serviced with a USB cable (through an
OTG adapter) or over Wi-Fi, and inspect, diagnose and repair it over ADB.
No computer is needed.

> Use it only on phones you own or have been asked to service. It does
> not remove screen locks, bypass account locks or flash firmware.

## What it does

| Tool | What it does on the connected phone |
| --- | --- |
| **Connection** | USB and Wi-Fi sessions, approval prompt guidance, cancel, "continue over Wi-Fi" from a cable session |
| **Diagnostics** | Device identity, battery, storage, memory, network, temperature, uptime, apps summary, full technician report |
| **Repair Tools** | 30 guided services (apps, network, storage, display, power). Each one explains itself, asks first, is checked afterwards and is recorded |
| **Apps** | List, inspect, back up the APK (verified by size and SHA-256), stop, disable, clear data, uninstall |
| **File Manager** | Browse shared storage, copy a file to this phone or send one to the connected phone |
| **Security Center** | Read-only audit with a score: patch age, root, bootloader, encryption, screen lock, risky settings, app sources |
| **Command Terminal** | A fixed list of read-only inspection commands. Free-form shell text is refused |
| **Repair History** | Every report and repair with the phone it was done on; export as text |

Diagnostics can also report on the workstation phone itself, and "Apps
on This Phone" manages its own apps.

## Using it

1. On the phone to be serviced: **Settings > About phone**, tap **Build
   number** seven times, then **Developer options > USB debugging**: on.
2. Plug an OTG adapter into the workstation phone and connect the two
   phones with a data cable (many cables only charge).
3. Open **Connection**, tap **Connect**, and allow USB access.
4. On the other phone, tap **Allow** on "Allow USB debugging?". The
   fingerprint on its screen should match the one shown in the app.

For Wi-Fi: connect by cable once, then tap **Continue over Wi-Fi**. Use
**Turn off Wi-Fi debugging** when the job is finished.

## Known limits

- The pairing code of Android 11+ "Wireless debugging" is not supported.
  Wi-Fi works through the cable-first method above.
- File transfer handles one file at a time, up to 2 GB.
- The ADB engine is covered by automated tests against a simulated
  phone. Test on real hardware before relying on a build.

## Getting the APK

Every push builds the app with GitHub Actions. Open the **Actions** tab,
choose the latest green run, and download **ultimate-debugging-tool-apk**
under Artifacts.

### Signing

Without a signing key each build is signed with a temporary key, and a
new build cannot be installed over an old one. To fix that once:

1. Create a key (on any computer with Java):

   ```sh
   keytool -genkeypair -v -keystore release.keystore -alias udt \
     -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 release.keystore > release.keystore.base64
   ```

2. In the repository open **Settings > Secrets and variables > Actions**
   and add four secrets:

   | Secret | Value |
   | --- | --- |
   | `SIGNING_KEYSTORE_BASE64` | contents of `release.keystore.base64` |
   | `SIGNING_STORE_PASSWORD` | the keystore password |
   | `SIGNING_KEY_ALIAS` | `udt` |
   | `SIGNING_KEY_PASSWORD` | the key password |

Keep `release.keystore` and its passwords safe and private. If they are
lost, users must uninstall before installing future versions. Never
commit the keystore; `.gitignore` already excludes it.

## Building on a computer

Needs Flutter (the version in `.flutter-version`) and Java 17.

```sh
flutter pub get
flutter analyze
flutter test                                   # Dart tests
flutter build apk --release
(cd android && ./gradlew :app:testDebugUnitTest)   # native engine tests
```

For a signed local build, create `android/key.properties`:

```properties
storeFile=release.keystore
storePassword=...
keyAlias=udt
keyPassword=...
```

and put `release.keystore` in `android/app/`.

## How it is built

```
lib/
  main.dart                    dashboard
  app/app_theme.dart           colours and shared widgets
  services/device_bridge.dart  the only place that talks to native code
  screens/                     one file per tool
android/app/src/main/kotlin/.../
  MainActivity.kt              routes requests, nothing else
  Adb*.kt                      ADB engine: protocol, USB and TCP transports,
                               authentication, streams, shell, file sync
  Target*.kt, SecurityAudit.kt, RepairServices.kt, TerminalCommands.kt
                               tools that work on the connected phone
  Local*.kt, Apk*.kt, Application*.kt, Usb*.kt
                               tools that work on the workstation phone
android/app/src/test/          native tests, run against a simulated phone
test/                          Dart tests
```

Safety rules built into the native side:

- The terminal accepts only its built-in read-only commands; arguments
  are validated and quoted.
- Repairs run only from a fixed catalog, need explicit confirmation, and
  refuse protected and system packages where that would be harmful.
- Copied files and APK backups stay in the app's private storage, are
  shared only through Android's share sheet, and are deleted after 24 h.
- The ADB private key is kept in the Android Keystore when the phone
  supports it.
