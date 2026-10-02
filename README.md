# Babble Bridge

Sideloadable Quest app: reads the Babble tracker over USB serial and serves
the camera frames as an MJPEG stream on port 8080. The Babble App on the PC
connects to `http://<quest-ip>:8080` like it would to a wireless tracker.

## Build & install
1. Open this folder in Android Studio and let Gradle sync (uses JitPack for
   usb-serial-for-android).
2. Build > Build APK(s).
3. With developer mode on, install the APK: `adb install app-debug.apk`
   (or use SideQuest).

## Use
1. Launch "Babble Bridge" from Library > Unknown Sources.
2. Plug the tracker into the Quest. Allow USB access and tick "always".
3. The app shows the stream address and tracker FPS.
4. Test first: open the address in a PC browser. You should see live video.
5. Put the address in the Babble App's camera field.

## Statuses
- "Waiting for tracker on USB": no serial device detected.
- "Connected, waiting for frames" with 0 FPS: USB is open, but no packets
  match the expected format (FF A0 FF A1 + uint16 LE length + JPEG).
- "Streaming": frames are flowing.

## Tests
`./gradlew test` runs FrameParserTest.
