#!/bin/sh
# Install the release APK for Android user 10.
#
# The app is force-stopped first: replacing it while a WineDroid task is open
# makes SystemUI's package-update relaunch crash (FileUriExposedException on
# a file:// URI for the running .exe), which restarts SystemUI, drops to the
# lock screen and takes the Linux Terminal VM down with it.
set -e
PATH=$PATH:$HOME/android-sdk/platform-tools
APK=${1:-$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk}
adb shell am force-stop --user 10 com.winedroid
adb install -r --user 10 "$APK"
