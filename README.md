# Nightfold

A fold-open animation for Samsung Galaxy Z Fold phones. As you open the phone, the cover screen's picture drains away
and the inner screen builds in with an effect that follows the hinge, covering the black gap Android leaves when it
switches panels. Closing plays it in reverse.

## Effects

27 effects, grouped in the app:

- **Eclipse:** R Eclipse Bloom (default), S Corona, U Constellation, V Nebula Burn
- **Bloom-style:** AI Honeycomb Bloom, AJ Lightning Veins, AK Stained Glass, AL Tile Flip, AM Spiral Galaxy,
  AN Kintsugi, AO Flower Bloom, AP Frosted Glass
- **More:** AA Eclipse Ink, AB Fireflies, AC Ember Burn, AD Starfall, AE Petal Storm, AF Frost Bloom, AG Aurora,
  AH Glitch Warp
- **Classic builds:** K Stardust, L Pixel Assemble, M Sandstorm, N Sketch to Paint, O Hologram, P Crystal, Q Raindrops

**Finish pass** (Off / A / B / C): once the fill is done, the effect sweeps across one more time (back the other way,
the same way again, or out from the middle) and leaves the solid screen behind it. Speed, blur and wobble sliders
tune it.

Other settings: cover and inner speed, "Learn my opening" (times a few of your own opens), picture slide on/off, and
home-screen capture so the effect draws your real icons and wallpaper.

## Requirements

- A Samsung Galaxy Z Fold, Android 14 or newer. Built and tested on a Z Fold (SM-F971U, Android 17). Other foldables
  use Android's public hinge sensor instead (coarser, untested).
- [Shizuku](https://github.com/RikkaApps/Shizuku) (free), required. It lets Nightfold read the fine hinge angle and
  draw above the system's panel switch. Without it the effect stays off and a notification asks you to start it.

## Setup

1. Download the latest `Nightfold-v*.apk` from [Releases](https://github.com/Vibe-Coded-Studio/nightfold/releases), install it and open it. A three-step setup screen walks you through it:
2. **Get Shizuku**, then **Start Shizuku** (the first time, Shizuku guides you through Wireless debugging).
3. **Allow**: one tap. The effect turns on.

After a reboot, start Shizuku again; Nightfold reconnects by itself and shows a notification if Shizuku is not
running.

## Build

```
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (compileSdk 35).

For a signed release build, point `NIGHTFOLD_SIGNING` in `~/.gradle/gradle.properties` at a properties file with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then run `./gradlew assembleRelease`.

## Known issues

- After an app update, Samsung sometimes does not deliver the "app updated" broadcast, so the effect stays off until
  you open Nightfold once.
- The cover-screen home capture only happens when the cover is unlocked on its home screen.

## Thanks

Nightfold stands on the work of everyone who explored fold animations before it. Thank you to:

- [duo-fold-live](https://github.com/joeconsorti/duo-fold-live), [duo-open](https://github.com/marcoazeem/duo-open),
  [duo-open-for-galaxy](https://github.com/OrangeHubX/duo-open-for-galaxy), [iphone-duo](https://github.com/chuspeeism/iphone-duo),
  [hingewave](https://github.com/Ant-lib/hingewave), [Folduo](https://github.com/bunkaich/Folduo) and its
  [Fold 8 fork](https://github.com/sjw1933/Folduo), [iphone-duo-animation](https://github.com/akashtdev/iphone-duo-animation),
  [Z-Fold-Duo-TEST](https://github.com/nnnnnnn0090/Z-Fold-Duo-TEST), [FoldFX](https://github.com/iamkeeler/FoldFX),
  [android-also-could-fold](https://github.com/keepYaoung/android-also-could-fold),
  [DuoFold-Android](https://github.com/jcx396905-gif/DuoFold-Android) and [its Vulkan fork](https://github.com/MineACEx/DuoFold),
  [fold-glass](https://github.com/SimonCym/fold-glass), [DuoFoldWallpaper](https://github.com/Vyom-2007/DuoFoldWallpaper)
  (all MIT; what each one gave us and their copyright notices are in [NOTICES.md](NOTICES.md))
- For inspiration (ideas only, no code used): [Duo-animation](https://github.com/Atomicx7/Duo-animation),
  [iphone-solo](https://github.com/soloiaros/iphone-solo), [FoldGlass](https://github.com/Abdul-RehmanCU/FoldGlass),
  [n6-fold-duo](https://github.com/chris-hemmings/n6-fold-duo), [duo](https://github.com/JSerFeng/duo)
- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps, and [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) by LSPosed

## License

MIT, © 2026 Vibe Coded Studio. See [LICENSE](LICENSE).
