# WineDroid

An Android app that runs Windows programs with [Wine](https://www.winehq.org/)
compiled **natively for x86_64 Android**. It uses no Box64, no QEMU and no
CPU emulation, so it needs an Android device with an Intel or AMD x86_64 CPU
(or an x86_64 emulator or Chromebook).

It uses Wine's own Android graphics driver (`wineandroid.drv`). Windows
programs draw into Android views, and touch, mouse and keyboard input goes
straight to Wine. Wine is built in "new WoW64" mode
(`--enable-archs=i386,x86_64`), so both 64-bit and 32-bit Windows programs
run, and no 32-bit Android libraries are needed.

## Runtimes: Wine and Proton

The launcher has a **Runtime** switch with two choices:

* **Wine 11.0** is upstream Wine.
* **Proton 11.0** is the Wine fork from Valve's Proton 11.0-2c. It carries
  Valve's game fixes and compatibility hacks on top of Wine 11.

Each runtime has its own Windows environment (its own `C:` drive and
registry), so programs installed with one don't show up in the other.
Only one runtime can run at a time. Switching while Windows programs are
running stops them.

This is Proton's Wine only. The rest of Proton (DXVK, VKD3D-Proton,
the Steam client bridge, its media stack) needs Vulkan, which
`wineandroid.drv` doesn't support, so it isn't included. Proton's esync and
fsync also stay off, because Android has no POSIX shared memory.

## Using it

* **Run a Windows program (.exe / .msi)…** opens the Android file picker.
  The app copies the file into its own storage and runs it, so installers
  work well. Installed programs go to `C:\Program Files` like on Windows.
* You can also use **Open with → WineDroid** on an `.exe` in a file manager.
* **Wine desktop** opens Wine's explorer. Use it to browse drives and start
  installed programs.
* Drive **D:** is your phone's shared storage (grant the storage
  permission). Drive **Z:** is the whole Android file system.
* Tap **⌨** in the Wine screen to show the on-screen keyboard. Each touch
  is a left click. USB and Bluetooth mice and keyboards work fully.
* **Stop Wine** ends every running Windows program.

The first launch of each runtime unpacks it and creates its Windows
environment (`wineboot`). This takes a few minutes.

## Limitations

* x86_64 devices only. ARM phones would need an emulator such as Box64,
  which this project deliberately leaves out.
* Graphics are 2D and OpenGL ES-backed. Wine's Android driver has no
  Vulkan, so DirectX 10/11/12 games won't work. Older DirectX and GDI apps
  are the sweet spot.
* No sound driver is built yet. Wine's Android build has no OpenSL/AAudio
  backend.
* `targetSdkVersion` is 28 on purpose. Wine has to `exec()` `wineserver` and
  its loader from the app's data directory, and newer target SDKs forbid
  that. So the app can't go on Google Play, but it installs fine by
  sideloading.

## Building

Tested on Debian 13 (x86_64).

```sh
sudo apt install openjdk-21-jdk-headless build-essential flex bison \
    gcc-mingw-w64-x86-64 gcc-mingw-w64-i686 librsvg2-bin cmake ninja-build \
    unzip zip xz-utils
# Android SDK (platform 34, build-tools 34) and NDK r27c:
export ANDROID_HOME=~/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/android-ndk-r27c

scripts/build-wine.sh ~/wine-work           # ~1-2 h: FreeType + Wine -> app/src/main/assets/wine
scripts/build-wine.sh ~/wine-work proton    # optional, ~1-2 h: Proton's Wine -> assets/proton
./gradlew assembleRelease                   # app/build/outputs/apk/release/app-release.apk
```

The launcher only offers the runtimes found in the assets, so you can skip
the Proton build to get a smaller APK.

`patches/` holds the fixes Wine 11.0 needs for Android: one so it compiles
against Android's C library (bionic), and one that brings `wineandroid.drv`
up to date with the 11.0 display driver interface. `patches/proton/` holds
the extra fixes Proton's Wine needs. `build-wine.sh` applies them.

## Layout

* `app/src/main/java/org/winehq/wine/WineActivity.java` is Wine's activity
  from `dlls/wineandroid.drv`. Its class name must stay the same because
  `ntdll` looks it up through JNI. It's extended to take a command line from
  the launcher, start extra programs as separate Wine processes, map drive D:
  and support the on-screen keyboard.
* `app/src/main/java/com/winedroid/LauncherActivity.java` is the home screen.
* `scripts/` holds the Wine/FreeType cross-build and asset packaging.

Wine is licensed under the LGPL 2.1 or later (see `COPYING.LIB`), and so is
this app.
