# USB DAC Output + Wireless Android Auto — Android 16

Force audio output to usb audio device/dac when android auto is running
Only tested on OnePlus 12 running LineageOS 26 with SukiSU
Mostly written with codex

## Why

After installing a aftermarket speaker, amp and a kicker keyloc in my Subaru VB WRX, I was still unsatified with the sound comming from my speakers. There isn't really an option to go with an aftermarket headunit in the VB WRX since a lot of the Eyssight controls and AC are intergrated into the stock starlink headunit. The stock headunit also features an unremovable eq curve for the stock speakers, which also really limits the bass output of the speakers. While the kicker keyloc and help reduce the bass rolloff and flatten the eq curve to a degree, there is still no option to fine tune the eq unless buying an expensive DSP. 
Furthermore, the bass rolloff also varied in volume. I get less bass at higher volumes and more bass at lower volume. 

To get around this, I decicded to directly connect my amp to an extertal dac via a usb hub to my phone. While I am able to achieve good audio quality with this method, the starlink screen became mostly unfunctional. It is still possible to connect to andoird auto with the center screen, however, once connected, the audio will be forced to routed through android auto. Since no speakers are connected to the stock headunit there are no sound as a result. 

I created this module to force android to keep outputing to the connected USB audio device while android auto is running. This way, I can get navigation displayed on the starlink headunit while still have sound going through the usb DAC, and I am able to use Viper4Andoid as my DSP. 

Microphone doesn't seem to work currently as Subaru disables the internal microphone when no speakers are connected to the starlink headunit. 
## Behavior introduced in 1.1

The supplied OnePlus dumps show `SMSL USB AUDIO` at `card=1;device=0`, recognized as a USB headset output. Apple Music is actively playing into Android Auto's 48 kHz remote submix, not into the DAC. Android Auto registers a separate 16 kHz mix for assistant and navigation audio. There is no active call or USB microphone in these snapshots. They predate this build and are evidence of the routing conflict, not validation of the module.

While the DAC is present, the daemon:

- Identifies Android Auto's actual package UID and matches its AudioService registration events to its live native capture mixes. It does not hardcode the dump's UID or registration addresses.
- Replaces the matching media/game and assistant/navigation capture criteria with a UID rule that cannot match an installed Android app. It preserves the registered capture endpoints rather than unregistering Android Auto's policy. Audio intended for those mixes can then take the normal USB output route; Android Auto's audio streams receive no matching playback.
- Requests USB as the preferred output for the normal playback strategies every two seconds. It skips internal patch/rerouting strategies and the call-assistant uplink strategy.
- Requests USB as the communication device with a process-owned binder token, retries every ten seconds when the reported device is different, and logs the reported route separately from request acceptance.

Mix checks run about every six seconds, plus immediately when the USB port id changes. Android Auto can still use the head-unit display and controls; this module targets its audio capture routing. Whether Android Auto stays connected with silent capture streams requires a phone/head-unit test.

## Install or upgrade

1. If an earlier version is installed, use its Action button to stop it first; reboot after installing the update.
2. Install `dist/usb-dac-force-1.2.0.zip` through KernelSU Manager → Modules.
3. Turn **off** Developer options → **Disable USB audio routing**.
4. Reboot, plug in the DAC, connect wireless Android Auto, and start playback. Pause/resume or restart the music app if it retains an old track route.
5. Verify music, a navigation prompt, assistant speech, a notification and a normal phone call individually. Requests accepted by the policy service are not proof of audible output.

For multiple USB devices, edit `/data/adb/modules/usb_dac_force/config.sh`, set `USB_ADDRESS` to the exact address from `probe.sh`, and reboot. The default selects the first USB output sorted by address and device id. The DAC must already be recognized by Android; the module does not add drivers or supported formats.

## Verify on the phone

Run in a **root shell**, with Android Auto and the DAC connected:

```sh
sh /data/adb/modules/usb_dac_force/probe.sh
cat /data/adb/modules/usb_dac_force/daemon.log
```

Expected: the probe identifies two Android Auto mixes whose native rules are `RULE_MATCH_UID 2147483647`, and the log reports that native capture rules were verified. Normal playback strategy preferences should point to your USB address. `Communication output reports USB port ...` means AudioService reports that device; verify the audio during a call too.

If it fails, collect diagnostics **while playing music**, and separately **during a call**:

```sh
sh /data/adb/modules/usb_dac_force/diagnose.sh
```

Files are placed in `/data/adb/modules/usb_dac_force/diagnostics/` and overwritten on the next collection; copy each collection before running again. Relevant files are `build.txt`, `probe.txt`, `daemon.log`, `audio.txt`, `policy.txt` and `logcat.txt`. Dumps/logcat can contain app names, device identifiers and other personal data; review them before sharing.

`No verified Android Auto capture mixes` means the module could not verify a supported mix from the available logs, or Android Auto is disconnected. It applies USB strategy preferences but makes no claim to bypass an unrecognized capture route. Unknown mix shapes or rules from other controllers are left alone. A routing error repeated five times stops the daemon and attempts restoration. Reboot to retry after correcting the error.

## Calls, microphone and limits

The build **requests every normal playback category on USB**, including call output. Root communication-device selection and preferred strategy requests are implemented, but actual call output has not been verified. This is an experimental device-targeted build, not a guarantee that every path is forced.

Microphone selection is left to Android; the phone's built-in mic may be used, but Bluetooth/call input behavior must be checked on the device. The module does not manufacture a USB input or force a capture preset.

Direct per-track routing, an app's own USB driver, hardware ultrasound paths and OEM restrictions may override normal strategy preferences. Notification mute/DND state and existing volumes are retained. There is no bit-perfect bypass, USB gain boost or speaker hardware lockout.

Native policy mix updates use Android 16 hidden framework APIs. AOSP 16 identifies the updated mix by its device type, address and route flags. The daemon reconstructs the observed supported mix descriptions, so it does not rely on Android 16's test-flag-gated registered-mix listing API. OEM signature or identity changes can reject the operation; the daemon logs this rather than changing global test flags or making SELinux permissive. Native criteria are verified in `dumpsys media.audio_policy` after updates. AudioService's framework policy config keeps the original rules for restoration.

## Stop, disconnect and uninstall

Disconnecting USB restores owned mix rules and playback preferences, then normal routing resumes. Allow several seconds for dump reads and route updates. KernelSU's Action button creates a `stop` file; the daemon restores its rules and preferences, releases its communication request and exits. Disabling/removing the module is also detected.

For uninstall, use Action, wait for `Stopped; preferences restored` in the log, remove the module, and reboot. Delete `/data/adb/modules/usb_dac_force/stop` and reboot to resume a stopped module. Installing an update clears the stop flag; reboot after upgrading.

Restoration checks native mix identity and the module's exact UID marker before replacing rules. It avoids overwriting another controller's newer rules. Prior playback preferences are restored only if they still match the module's last preference. Mix descriptions retained in memory allow restoration even when AudioService's finite registration event log rotates. A surviving marker can also be recovered from the unchanged framework config on the next daemon start, if ownership can still be verified. Abrupt termination may leave preferences/mix criteria until recovery or reboot; use Action for graceful stopping.

## Build and validation

`bash build.sh` requires Android SDK platform 36, build tools 36.0.0 and a JDK. Defaults use `$HOME/Android/Sdk` and Android Studio's bundled JBR; override `ANDROID_HOME`/`JAVA_HOME` as needed. Sources are in `src/dev/usbdac/`.

Local checks: compile Java, generate DEX, check shell syntax and ZIP integrity. `tests/StrategyAccessTest.java` reproduces the missing-binder-method condition and checks both binder variants, native fallback, empty results and permission-error propagation. `tests/RoutingDumpTest.java` exercises sanitized fixtures representing the observed OxygenOS and LineageOS mix layouts, package ownership, native confirmation, dynamic registration addresses, marker recovery and rejection of another controller's changes. Run `bash test.sh` to execute the regression tests. The fixtures use fictional UIDs and registration addresses. Personal phone dumps and diagnostics are excluded from this repository; tests are not packaged in the module ZIP.

No Android phone or head unit was attached to the build environment. The owner subsequently confirmed v1.2 works on their LineageOS setup. Other ROMs, individual navigation/call behavior and microphone selection still need device-specific validation.

References: [Android audio routing](https://source.android.com/docs/core/audio/combined-audio-routing), [Android 16 native mix matching](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp), [Android 16 AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/audio/AudioService.java), [KernelSU module guide](https://kernelsu.org/guide/module.html).

