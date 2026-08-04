# Shipping Fuse to Google Play

Two halves: the ads are wired up and verified in CI, and the Play Console work
is yours. This is the exact order to do it in.

**Status of the code:** AdMob interstitials are integrated and building green.
They currently serve **Google's official test ads** - the build falls back to
test IDs whenever real ones are not supplied, and debug builds are pinned to
test IDs no matter what. Nothing is live until you do step 1.

---

## 1. AdMob

1. Sign in at [apps.admob.com](https://apps.admob.com), then **Apps -> Add app
   -> Android -> No** (not yet on Play). Name it `Fuse`.
2. Copy the **App ID**: `ca-app-pub-XXXXXXXX~YYYYYYYY` (tilde).
3. **Ad units -> Add ad unit -> Interstitial**, named something like
   `Fuse - game over`. Copy the **Ad unit ID**:
   `ca-app-pub-XXXXXXXX/ZZZZZZZZ` (slash). The tilde one and the slash one are
   different things, and swapping them is the classic first mistake.
4. Put both in your **user-level** Gradle properties, not in this repo -
   `C:\Users\<you>\.gradle\gradle.properties`:

   ```properties
   ADMOB_APP_ID=ca-app-pub-XXXXXXXX~YYYYYYYY
   ADMOB_INTERSTITIAL_ID=ca-app-pub-XXXXXXXX/ZZZZZZZZ
   ```

   The build reads these and bakes them into release builds only. They never
   enter git.
5. Once the app is live, come back and **link the AdMob app to Play**
   (AdMob -> App settings -> Link to Play). Without it you lose a chunk of ad
   revenue and all the Play-side metrics.

> **Never click your own live ads, including "just to check".** It is the
> quickest route to a permanently suspended AdMob account. That is exactly why
> debug builds here cannot serve anything but test ads.

## 2. A signing key

### Option A: build locally

Play needs a signed AAB. Google holds the real app signing key (Play App
Signing); you hold an **upload key**.

You need a JDK for `keytool`. Installing Android Studio gives you one, and you
will want it for step 3 anyway.

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

It will ask you to invent a password. **Choose it yourself and put it in your
password manager** - if you lose this file or its password you cannot ship
updates to the same listing, ever. Back the `.jks` up somewhere that is not this
laptop.

Then create `keystore.properties` in the repo root (already gitignored):

```properties
storeFile=../upload-keystore.jks
storePassword=<yours>
keyAlias=upload
keyPassword=<yours>
```

`app/build.gradle.kts` picks this up automatically and signs release builds with
it. Without the file, release builds fall back to the debug key - fine for
sideloading, **rejected by Play**.

### Option B: sign in CI, no local toolchain

You have `openssl` (it ships with Git for Windows) but no JDK, so this path
makes the keystore with openssl and lets GitHub Actions do the signing.

**1. Create the upload key.** Run this yourself and pick your own password when
prompted — it must never be shared, pasted into a chat, or committed:

```bash
openssl req -x509 -newkey rsa:2048 -sha256 -days 10000 -keyout upload.key -out upload.crt -subj "/CN=Mylonas/O=Mylonas/C=CY"
```

```bash
openssl pkcs12 -export -inkey upload.key -in upload.crt -name upload -out upload-keystore.p12
```

The first command asks you to invent a PEM passphrase; the second asks for an
export password. **Use the same password for both** and store it in your
password manager. Then delete `upload.key` and `upload.crt` — the `.p12` is the
only file you need.

> Back `upload-keystore.p12` up somewhere that is not this laptop. Lose it and
> you cannot ship updates to the same Play listing. (An *upload* key can be
> reset by Google if it comes to that, unlike the app signing key — but resetting
> is a support round-trip you do not want.)

**2. Turn it into base64:**

```bash
base64 -w 0 upload-keystore.p12 > upload-keystore.b64
```

**3. Add six repository secrets** — GitHub → your repo → **Settings → Secrets
and variables → Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the entire contents of `upload-keystore.b64` |
| `KEYSTORE_PASSWORD` | the password you chose |
| `KEY_ALIAS` | `upload` |
| `KEY_PASSWORD` | the same password |
| `ADMOB_APP_ID` | `ca-app-pub-XXXX~YYYY` |
| `ADMOB_INTERSTITIAL_ID` | `ca-app-pub-XXXX/ZZZZ` |

Paste them into GitHub yourself. I never see them, and GitHub masks secret
values in workflow logs.

**4. Run the workflow.** Actions → **Signed release bundle** → *Run workflow*,
and give it a `versionCode` higher than anything you have uploaded before
(start at `2`) and a `versionName` like `1.1`.

It refuses to produce anything unusable: the build fails if a signing key is
missing, if the AdMob ids are still the test ones, and it runs `apksigner` at
the end to prove the output is not debug-signed. Download the artifact — the
`.aab` inside is what you upload to Play.

Keep `mapping.txt` from the same artifact. Upload it alongside the bundle so
Play can deobfuscate crash reports.

### Which option to pick

Option A if you are going to install Android Studio anyway — you will want it
for screenshots and for testing on a device. Option B if you would rather not
install a toolchain: it puts your upload key in GitHub's encrypted secret store,
which is normal practice for CI signing, but it is your key and your call.

## 3. Build the AAB

Bump the version first, in `app/build.gradle.kts`. `versionCode` must increase
on every single upload:

```kotlin
versionCode = 2
versionName = "1.1"
```

**Android Studio (recommended for a first release):** open the folder, let it
generate the Gradle wrapper, then `Build -> Generate Signed App Bundle`. You
will want Studio for the screenshots in step 4 regardless.

**Command line**, with JDK 17 and the Android SDK installed:

```bash
gradle bundleRelease
```

The bundle lands at `app/build/outputs/bundle/release/app-release.aab`.

> The `android.yml` workflow's AAB is signed with the **debug** key and cannot
> be uploaded. Use the **Signed release bundle** workflow (option B in step 2)
> for anything going to Play.

## 4. Play Console

**Create the app** at [play.google.com/console](https://play.google.com/console).
App name `Fuse`, free, it is a **game**, category **Puzzle**, package
`com.mikmy.fuse` - the package name can never be changed afterwards.

**Store listing** - all of this is required before you can submit:

| Asset | Requirement |
| --- | --- |
| App icon | 512x512 PNG, 32-bit, under 1 MB |
| Feature graphic | 1024x500 PNG or JPEG |
| Phone screenshots | 2 to 8, at least 320px on the short side |
| Short description | 80 characters |
| Full description | 4000 characters |

`store/icon-512.svg` and `store/feature-graphic-1024x500.svg` in this repo are
starting points - open either in a browser or Inkscape and export as PNG at the
exact size. For screenshots, run the game and capture;
`.github/workflows/emulator.yml` already produces gameplay screenshots as a CI
artifact if you want a shortcut.

Suggested short description:

> Drop orbs. Two of a kind fuse. Don't overflow the well.

**Content rating** - fill in the questionnaire. These games have no violence, no
user-generated content and no purchases, so they land in the lowest brackets.

**Ads declaration** - *Yes, my app contains ads*. Not optional, and
misdeclaring it is a suspension risk.

**Data safety** - the section people get wrong. With AdMob integrated the honest
answers are:

- Does your app collect or share user data? **Yes**
- Data type: **Device or other IDs -> Advertising ID**, collected *and* shared,
  purpose **Advertising or marketing**
- Not processed ephemerally, not user-deletable
- Encrypted in transit: **Yes**

The game itself stores only your high score, on the device, and transmits
nothing. Everything above is the ads SDK. Check the answers against
[AdMob's data disclosure guidance](https://support.google.com/admob/answer/11221321)
before submitting, since Google revises the form.

**Privacy policy** - **required**, because the app collects the advertising ID.
`PRIVACY.md` here is a ready template. Fastest way to host it: push it to a
public GitHub repo, enable **Settings -> Pages**, use the resulting URL. Fill in
your contact email first.

**Target audience** - choosing 13+ keeps you out of the Families policy
programme and its extra ad restrictions. Selecting an under-13 audience means
switching to child-appropriate ad settings in AdMob too.

**App access** - no login required, tick that.

## 5. The 12-testers rule

If your developer account is a **personal** account created after
**13 November 2023**, you cannot publish straight to production. You must run a
**closed test with at least 12 testers opted in for 14 consecutive days**, then
apply for production access
([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465)).

- "Opted in" means they accepted the invite *and installed the app* under the
  matching Google account. Invited-but-not-installed does not count.
- The 14 days must be unbroken, and must be the most recent 14 at the moment you
  apply.
- Organisation accounts, and personal accounts older than that date, are exempt.

Check which kind of account you have before planning a launch date. This is the
most common surprise, and it is two weeks of calendar time.

## 6. Release

1. **Testing -> Internal testing** first. Upload the AAB, add yourself, install
   from the opt-in link, and confirm on a real device that the game runs, that
   an interstitial appears after a few runs, and that the consent dialog appears
   if you are in the EEA.
2. Then **Closed testing** for the 12-tester run, if step 5 applies to you.
3. Then **Production**. A first review usually takes a few days.

## Gotchas that bite

- **`versionCode` must increase** on every upload. The same number is rejected.
- **targetSdk 35** is already set; Play enforces a recent target API for new apps.
- The app will show ads to *you* in production. Do not tap them.
- If no interstitial appears, that is the frequency cap: nothing for the first 3
  runs, then at most one per 3 runs and never within 90 seconds of the last one.
  Tune it in `Ads.kt`.
- A missing `com.google.android.gms.ads.APPLICATION_ID` meta-data crashes the
  app on launch. It is already in the manifest, wired to the Gradle property.

## What is still untested

Nobody has played these games with their hands. The balance is bot-measured and
the code is CI-verified on an emulator, but "is it fun" has not been answered.
Internal testing in step 6 is the first real chance to find out - do that before
spending two weeks on a closed test.
