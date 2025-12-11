# ShiggyXposed

ShiggyXposed is an Xposed module (Kotlin) that injects the ShiggyCord client modifications into the official Discord Android application. It provides an in-app recovery/dev menu (LogBox) that lets you manage bundle loading, themes, and other developer helpers.

This README explains prerequisites, installation, usage, troubleshooting, and credits. If you want help, open an issue on the repository or join the project's support server (links below).

---

## Table of Contents

- [What is this](#what-is-this)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Using the Module](#using-the-module)
  - [Recovery / Options / Themes menu](#recovery--options--themes-menu)
  - [Bundle injection / Custom bundle URL](#bundle-injection--custom-bundle-url)
- [Development / Building](#development--building)
- [Troubleshooting & FAQ](#troubleshooting--faq)
- [Contributing](#contributing)
- [Credits](#credits)
- [License](#license)
- [Contact & Links](#contact--links)

---

## What is this

ShiggyXposed injects a patched ShiggyCord bundle into the official Discord application by using the Xposed framework. It's intended for users who run a rooted Android device and want to apply client-side modifications to Discord.

---

## Features

- Inject a custom ShiggyCord bundle into Discord.
- Toggle bundle injection on/off (marker-based).
- Load a custom bundle URL and toggle it on/off.
- Recovery/LogBox action sheet for reload, safe-mode, refetch/reset, and options.
- Options to change the LogBox menu appearance (System / Light / Dark) and a set of menu color "flavors".
- In-place UI theming for the recovery/options menus.
- Helpers for refetching or backing up the currently loaded bundle.

---

## Prerequisites

Before installing and using ShiggyXposed:

- A rooted Android device. Supported root solutions you might use:
  - Magisk, KernelSU, KernelSUNext, or Sukisu Ultra.
- Xposed framework installed. The project targets LSPosed or forks because of compatibility with modern Android and Discord:
  - Recommended: JingMatrix Fork of [LSPosed](https://github.com/JingMatrix/LSPosed)
- The official Discord Android application (stable/beta/alpha) installed and included in the module scope.

---

## Installation

1. Download the latest APK from the repository Releases:
   - https://github.com/kmmiio99o/ShiggyXposed/releases
2. Install the APK on your device (standard package install).
3. Open your LSPosed manager (or chosen Xposed manager).
4. Enable the ShiggyXposed module and ensure the module's scope includes the Discord app.
5. Reboot your device (or restart the system process) for changes to take effect.

---

## Using the Module

Once installed and active, you can open the bundled Recovery/LogBox menu (depending on the hooked RN dev dialog) to access all tools.

Recovery → Options → Themes:
- Recovery (Action Sheet) includes:
  - Safe Mode toggle (toggle persisted safe-mode setting + optional theme handling)
  - Load Custom Bundle
  - Reload App
  - Options (opens the Options action sheet)
- Options includes:
  - Themes (open appearance/flavor controls)
  - Toggle bundle injection (rename/add marker file in cache to prevent injection)
  - Refetch Bundle (moves or resets current bundle and triggers a reload)
  - Clear Cache & Reset (clears cached bundle and resets loader config)
  - Back (return to Recovery)

Themes:
- Pick appearance: System / Light / Dark
- Choose Menu Color Flavor: blue, green, mocha, vanilla, purple, amber, teal
- Theme & flavor settings persist under the app's files directory (`files/logbox/LOGBOX_SETTINGS` and `files/logbox/LOGBOX_THEMES`)

Bundle injection:
- When you disable injection, the module will create/rename a marker file in the cache directory so other components (loader) skip injection.
- Toggling injection is handled by `toggleBundleInjection(context)` and is persisted to `LOGBOX_SETTINGS`.

Custom bundle URL:
- The custom bundle loader configuration is stored in `files/pyoncord/loader.json`.
- You can enable/disable the custom URL and provide a remote URL (the module exposes a dialog for this).

---

## Development / Building

If you want to build the project locally:

- Prerequisites: JDK, Android SDK, and Gradle (the project uses the Gradle wrapper).
- From the repository root:

  - Build debug APK:
    ```
    ./gradlew :app:assembleDebug
    ```

  - Install to connected device (adb required):
    ```
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```

- Run and test on a rooted device/emulator with Xposed/LSPosed installed and Discord in scope.

Notes:
- The project is written in Kotlin and uses Android UI primitives to construct the recovery/LogBox UI. The dev menu is created in-place to avoid modifying Discord views.

---

## Troubleshooting & FAQ

1. Module not working or not showing in Discord?
   - Ensure ShiggyXposed is enabled in LSPosed and Discord is included in the module's scope.
   - Reboot after enabling the module.
   - Confirm you have the compatible LSPosed fork installed (JingMatrix recommended).

2. Recovery menu shows visual glitches when changing themes?
   - The menu attempts to apply colors in-place. If you see transient visual artifacts, try closing and reopening the menu — the settings are persisted and will apply on reopen.
   - If an area does not update, please report which dialog/control (and the device/Android version) so it can be targeted.

3. Bundle injection not toggling?
   - Toggling injection manipulates files under the app's cache directory. If that fails, check file permissions or whether the cache path changed on your build of Discord.
   - You can also manually inspect `data/data/<discord.package>/cache/...` to see `.disabled` marker files (depending on your device and build).

4. I accidentally broke Discord — how do I recover?
   - Use the Recovery → Options → Clear Cache & Reset option to remove cached bundles and restore default loader settings, then reload Discord.
   - If Discord fails to start, reboot the device to make sure changes are applied cleanly.

If you still have issues, open an issue on GitHub or join the support server (link below). Please include:
- Device model and Android version
- Discord app version
- A clear description of the problem and steps to reproduce
- Any relevant logs or screenshots (redact personal data)

---

## Contributing

Contributions are welcome. If you want to help:

- Open an issue to discuss a bug or feature.
- Send pull requests with focused, well-documented changes.
- When working on UI or theme changes, test on multiple screen sizes and Android versions if possible.

Guidelines:
- Keep commits small and descriptive.
- Include code comments for complex logic (hooks, file-system operations).
- Respect user privacy — do not add telemetry or data exfiltration.

---

## Credits

This project stands on the shoulders of many open-source projects and contributors:

- [LSPosed Team](https://github.com/LSPosed) — the Xposed framework implementation used to run modules.
- [JingMatrix](https://github.com/JingMatrix) — author of the LSPosed fork referenced by many users.
- [cocobo1](https://codeberg.org/cocobo1) — for adapting some features to Xposed which [Revenge Team](https://github.com/revenge-mod) didn't.
- [Revenge Team](https://github.com/revenge-mod) — provided earlier Xposed module work that informed parts of this project.

---

## License

Please refer to the `LICENSE` file in the repository root for the project license (if present). If no license file exists, the repository's default (copyright) status applies — check the repo settings or ask the maintainer for clarification.

---

## Contact & Links

- Releases: https://github.com/kmmiio99o/ShiggyXposed/releases
- Repository: https://github.com/kmmiio99o/ShiggyXposed
- Support server: https://discord.gg/nQykFF9Ud6

---

Thank you for using ShiggyXposed.
