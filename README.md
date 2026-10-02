# Babble Bridge

Sideloadable Quest app: reads the Babble tracker over USB and serves the
camera frames as an MJPEG stream on port 8080. The Babble App on the PC
connects to `http://<quest-ip>:8080` like it would to a wireless tracker.

Both kinds of tracker are supported: ones that show up as a USB camera (UVC,
such as OpenIris firmware) and ones that send frames over USB serial.

<img width="969" height="596" alt="image" src="https://github.com/user-attachments/assets/d83f63d7-2ec2-437f-8b34-797e87c0d6c4" />

## Install
1. Download `BabbleBridge.apk` from the
   [latest release](https://github.com/bkacjios/BabbleBridge/releases/latest).
2. Enable developer mode on the Quest (Meta Horizon phone app > Devices >
   Developer Mode) and connect it to your PC with a USB cable. Allow USB
   debugging when the headset asks.
3. Sideload the APK, either by dragging it onto SideQuest or with adb:
   `adb install -r BabbleBridge.apk`

To update, install the newer APK the same way. `-r` keeps your settings.

## Use
1. Launch "Babble Bridge" from Library > Unknown Sources.
2. Plug the tracker into the Quest. Allow USB access and tick "always".
3. The app shows the stream address and tracker FPS.
4. Test first: open the address in a PC browser. You should see live video.
5. Put the address in the Babble App's camera field.

## Statuses
| State | Message |
|---|---|
| 🟢 Streaming | Receiving frames from the tracker |
| 🟡 Connected | Connected, waiting for frames |
| 🟡 Waiting for tracker | Plug the tracker into the headset |
| 🟡 Waiting for tracker | Allow USB access in the headset dialog |
| 🔴 Problem | Allow camera and USB camera access so the app can use the tracker |
| 🔴 Problem | USB access denied. Replug the tracker or reopen the app to ask again. |
| 🔴 Problem | Couldn't open the USB device |
| 🔴 Problem | Couldn't open port 8080: … |
| ⚪ Stopped | Bridge is off |

If it stays on "Connected" with 0 FPS, the tracker is open but isn't sending
valid frames. Serial trackers must send `FF A0 FF A1` + uint16 LE length + JPEG.

If the tracker disconnects or stops sending frames, the bridge reconnects to it
automatically.

## Building from source
1. Open this folder in Android Studio and let Gradle sync (uses JitPack for
   usb-serial-for-android).
2. Build > Build APK(s), or `./gradlew assembleDebug`. The APK is written to
   `app/build/outputs/apk/debug/app-debug.apk`.
3. Install it with `adb install -r app-debug.apk`.

A debug build is signed with a different key than the releases, so uninstall
the release version before installing one (and vice versa).

`./gradlew test` runs the unit tests.
