# Working notes

What was tried while building Halo for a Fire TV Stick, what worked, and what didn't.
Written up on 2026-11-08.

**Test device:** Fire TV Stick 3rd gen (model AFTSSS), Fire OS 7.7.1.7 (Android 9, API 28),
reached over ADB on Wi-Fi.
**Also tested on:** an Android 16 (API 36) phone emulator in landscape.

## The goal

Replace the Fire TV home screen (ads, sponsored rows) with a fast, ad-free launcher with a
premium, big-hero TV UI. Priorities: no bloat, fast navigation and launch, polished look.

## Outcome in one paragraph

Halo itself works well: a cold start took about 0.65 s on the stick, it used about 91 MB, and
nothing crashed. **But Fire OS won't let a third-party app become the home screen.** Every route
to owning the Home button is reserved for Amazon-signed apps. The best possible result is that
Home shows Amazon's screen and Halo takes over **about 5 seconds** later. That isn't the
"new OS from the first second" this project wanted, so Halo was uninstalled from the stick. It
remains a good fit for Android TV devices that allow a custom default launcher.

## What worked

### The launcher
- **Plain Java, no AndroidX, no libraries.** Release APK about 115 KB with R8 minify and resource
  shrinking. Builds with AGP 9.0.1 / Gradle 9.1.0 / JDK 17.
- **App discovery**: `LEANBACK_LAUNCHER` apps first (they have 16:9 banners), then `LAUNCHER`
  apps, deduplicated by package. `<queries>` entries are needed for package visibility on API 30+.
- **Sharp icons**: load icon resources at `DENSITY_XXXHIGH` so tiles stay crisp when scaled up.
- **Blurred hero backdrops without RenderEffect** (which needs Android 12): draw the banner at
  256×144, run a 2-pass box blur in pure Java, and let the ImageView scale it up. Made on a
  background thread when first needed; Favorites are prepared ahead of time.
- **Clean tile colors**: a hue-histogram "dominant color" weighted by saturation² × value, so
  multi-color icons pick one clean hue instead of mud. Greys map to graphite.
- **Frosted glass**: take a 1/8-scale snapshot of the root view, blur it, and draw it offset by the
  panel's *layout* position. Using `getLocationOnScreen` broke during the slide-in animation
  (the panel was off-screen when the offset was recorded), which made the glass see-through.
- **Motion**: a damped-cosine spring curve, a tilt toward the direction of travel, a shine sweep
  on focus, and a slow zoom-and-drift on the hero. Rows are driven by Halo, not by the system's
  auto-scroll (`computeScrollDeltaToGetChildRectOnScreen` returns 0), which keeps movement
  predictable.
- **Fonts**: Inter is a variable font; static 400/500/600 instances were made with fonttools,
  subset to Latin (about 52 KB each), and loaded from `assets/` because `res/font` needs API 26.
- **Odd-shaped banners** (e.g. a very wide Live Shopping banner) are centered on a gradient
  instead of cropped.

### Fire OS integration
- **Fire's settings pages that other apps may open directly**: Network, Controllers & Bluetooth,
  Applications and My Fire TV (`com.amazon.tv.settings.v2/.tv.*Activity`). All four were opened
  on the stick from Halo.
- **Screen mirroring**: `com.amazon.cast.sink/.DisplayMirroringSinkActivity` is exported and
  opens fine.
- **Home redirect via accessibility service**: listening for `TYPE_WINDOW_STATE_CHANGED` from
  `com.amazon.tv.launcher` and starting Halo does bring Halo back after Home, just slowly
  (see below).
- **Turning off ad/telemetry extras with `pm disable-user --user 0`** worked for: Live Shopping
  (`com.amazon.shoptv.client`, `com.amazon.shoptv.firetv.client`), content recognition
  (`com.amazon.tv.acr`), Game Hub (`com.amazon.gamehub`), and metrics collectors
  (`com.amazon.tv.fw.metrics`, `com.amazon.wirelessmetrics.service`, `com.amazon.perfcollection`,
  `com.amazon.perfc`). Netflix kept playing during the change and nothing crashed.
  Undo: `adb shell pm enable <package>`.

## What failed, and why

| Attempt | Result | Why |
|---|---|---|
| Set Halo as default launcher | Not possible | Fire OS has no default-home setting and ignores other `HOME` apps |
| Catch the Home key (`flagRequestFilterKeyEvents` + `onKeyEvent`) | Key never reaches the service, from the real remote or ADB | Fire OS handles Home before the accessibility key filter |
| Amazon's own "custom Home" hook (`com.amazon.tv.action.CUSTOM_HOME`) | Blocked | Needs `RECEIVE_CUSTOM_HOME`, protection level `signature\|amazon` |
| Hold-Home menu hook | Blocked | `RECEIVE_HOME_LONGPRESSED_ACTION` is `signature\|amazon` |
| React to the "Home pressed" broadcast (`CLOSE_SYSTEM_DIALOGS`, `reason=homekey`) | Fire sends it, but the receiver in the accessibility service never logged it | Not resolved |
| Open Halo sooner after Home | Halo still appears about 5 s after the press | Android 9's "app switch" protection holds activity starts from other apps for 5 s after Home; Amazon's launcher doesn't release it early |
| Ask for Halo early (at the Home signal) | Made it **worse** (5–7 s) and, with de-duplication, a second Home press during the hold dropped the request and left the user on Amazon's home | Held requests are discarded on the next Home press |
| Switch off Amazon's launcher (`pm disable-user com.amazon.tv.launcher`) | `SecurityException: Cannot disable a protected package` | Fire OS keeps a protected-package list |
| Disable some ad/telemetry packages | Refused for `com.amazon.ftvads.deeplinking`, `com.amazon.device.metrics`, `com.fireos.usagestats.proxy` | Same protected-package list |
| Sleep via Fire's own sleep broadcast | Not allowed | `com.amazon.permission.CALL_GO_TO_SLEEP` is Amazon-only; Halo uses `GLOBAL_ACTION_LOCK_SCREEN` from the accessibility service instead |
| Open Display & Sounds, Accessibility, Preferences or Account pages | Not allowed | Need `com.amazon.tv.permission.LAUNCHER_SETTINGS` (`signature\|privileged`) |
| Open Fire's main Settings with the redirect on | Bounced straight back to Halo | Fire's top-level Settings lives *inside* Amazon's home activity (`com.amazon.tv.launcher/.ui.SettingsActivity` → `HomeActivity_vNext`). Fixed by pausing the redirect for 4 s when Halo opens it |

### Measured Home timings (Fire TV Stick, real remote)

| Build | Home press → Halo on screen |
|---|---|
| Redirect on Amazon's home window | Halo requested 1.2–2.1 s after the press; on-screen time not measured |
| Request at "Home pressed" signal + de-duplication | about 5.2–5.7 s; a second press could leave you stuck on Amazon's home |
| Request at Amazon's home window, plus a second request | about 5.5–7 s |

Last build (not installed): while Amazon's home is in front and Halo isn't, Halo asks again once
a second, so it always arrives about 5 s after the *last* Home press and you never stay stuck.

## Emulator gotchas

- An API 36 emulator does **not** deliver ADB-injected or emulator-console key events to an
  accessibility key filter, so Home interception can't be tested there.
- API 31+ no longer delivers `CLOSE_SYSTEM_DIALOGS` to apps targeting S or later.
- Switching the emulator renderer invalidates the quick-boot snapshot (slow cold boot). Restarting
  the ADB server fixed an "unauthorized" emulator.

## Process notes

- Check the TV's foreground app (`dumpsys window | grep mCurrentFocus`) **before** sending any key
  events. Twice during testing the TV was on another screen (Netflix, then the screensaver), and
  injected keys ended up somewhere unintended.
- Fire OS log timings were the most reliable measuring tool: `ActivityManager: START` and
  `Displayed` lines show exactly when a request was made and when the screen appeared.

## Where Halo would make sense next

On Android TV / Google TV hardware that lets you choose a default home app (Nvidia Shield,
onn. 4K boxes), Halo's `HOME` intent filter is enough: it becomes the real home screen with an
instant Home button and no redirect service.
