# Piña Admin

Android app for the Piña admin site: admin.mypina.co.uk in a web view, plus two native screens:
**Write tag** personalises an NTAG 424 DNA tag (URL, SDM and keys) in one go, and **Verify tag**
reads a tag the way a customer's phone would and asks the server whether it checks out. See
`guide.md` for the plan.

## Tooling (macOS, no Android Studio needed)

```sh
brew install --cask temurin@17                 # JDK 17
brew install --cask android-commandlinetools
brew install maven                             # to build ntag424-java

export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Gradle reads the SDK path from `ANDROID_HOME` or from `local.properties`
(`sdk.dir=/opt/homebrew/share/android-commandlinetools`, not committed).

## The ntag424 library

[johnnyb/ntag424-java](https://github.com/johnnyb/ntag424-java) is vendored as a jar built from a
pinned commit, because its 1.0.x API is not stable. Anything in `app/libs/*.jar` is compiled in.

```sh
git clone https://github.com/johnnyb/ntag424-java /tmp/ntag424-java
cd /tmp/ntag424-java
git checkout ccf4753                          # pinned commit (matches app/libs/ntag424-ccf4753.jar)
mvn -q -DskipTests package
cp target/ntag424-*.jar "<this repo>/app/libs/"
```

## Build and install

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable USB debugging on the phone, or pair over Wi-Fi with `adb pair`.

## Debug vs release

| Build   | `BuildConfig.ADMIN_BASE_URL`   | Cleartext                                                  |
|---------|--------------------------------|------------------------------------------------------------|
| debug   | `http://localhost:3000`        | allowed for `localhost`, `10.0.2.2`, `admin.localhost` only |
| release | `https://admin.mypina.co.uk`   | none                                                       |

### Release signing

The release build is signed with a keystore that lives outside the repo. Create it once:

```sh
mkdir -p ~/.pina-admin && chmod 700 ~/.pina-admin
openssl rand -base64 30 | tr -d '/+=' > ~/.pina-admin/keystore.password
keytool -genkeypair -keystore ~/.pina-admin/release.jks -alias pina-admin \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass:file ~/.pina-admin/keystore.password -keypass:file ~/.pina-admin/keystore.password \
  -dname "CN=Pina Admin, O=Pina"
```

Then give it to GitHub Actions as repository secrets (the release job decodes it):

```sh
gh secret set KEYSTORE_BASE64 --body "$(base64 -i ~/.pina-admin/release.jks)"
gh secret set KEYSTORE_PASSWORD < ~/.pina-admin/keystore.password
```

Locally, `app/build.gradle.kts` reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD` and optionally
`KEY_ALIAS` (default `pina-admin`) and `KEY_PASSWORD` (default: the store password):

```sh
KEYSTORE_FILE=~/.pina-admin/release.jks KEYSTORE_PASSWORD="$(cat ~/.pina-admin/keystore.password)" \
  ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without `KEYSTORE_FILE` the release APK is unsigned and won't install. Debug and release share the
application id, so switching between them on a phone needs `adb uninstall uk.co.mypina.admin`
first (the signatures differ). Back up `~/.pina-admin`: losing the keystore means every future
release build has to be reinstalled from scratch on each phone.

### CI

`.github/workflows/android.yml` runs the unit tests and builds the debug APK on every push and
pull request (download it from the run's artifacts). Pushing a tag like `v0.2.0` also builds the
signed release APK and attaches both APKs to a GitHub Release. The release job is skipped when the
`KEYSTORE_BASE64` secret is not set.

### Pointing a debug build at the dev server

`admin.localhost` doesn't resolve on a phone, so the debug build uses plain `localhost:3000` and
forwards it over USB to the Mac:

```sh
# in the Piña repo
pnpm dev                                       # Next.js on :3000

# with the phone plugged in
adb reverse tcp:3000 tcp:3000                  # phone's localhost:3000 -> Mac's :3000
```

Re-run `adb reverse` after reconnecting the phone. The dev server must serve the admin routes for
host `localhost` (the guide's host routing treats localhost as admin in dev). On the emulator,
`10.0.2.2:3000` also works (edit the debug `ADMIN_BASE_URL` in `app/build.gradle.kts`).

## How it fits together

- `MainActivity` owns one `WebView` (history survives trips to the Write screen). User-Agent ends
  in ` PinaAdmin/<versionName>`, which makes the site show **Write with this phone**, a link to
  `pina-admin://write-tag/<tagId>`. The web view intercepts it and opens the Write screen.
- `pina-admin://verify-tag` or `pina-admin://verify-tag/<tagId>` (same id pattern, optional)
  opens the Verify screen; see [Verify tag](#verify-tag).
- Links off the admin host open in the phone's browser. Back walks web history; exits at the root.
- Both native screens sit in `ui/NfcReaderScreen` (top bar, back = close, screen kept on, NFC
  reader mode with `FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK` only while resumed, no-NFC /
  NFC-off notice) and get a fresh ViewModel per visit from `ui/ScopedViewModelStore`. Tags are
  connected with `nfc/IsoDepConnect.kt` (`IsoDep.get`, `connect()`, 5 s timeout). Done on either
  screen returns to the web view and reloads the page.
- The Write screen (`write/`) also marks the window `FLAG_SECURE` (keys pass through it). It
  hands a connected `IsoDep` to a `TagWriter` on `Dispatchers.IO`.
- `api/AdminApi` calls `POST /admin/api/tags/{id}/personalise`, `.../personalised` and
  `/admin/api/tags/verify` with the web view's `pina_a` cookie, no redirects followed; 401 or 3xx means "Sign in on the website first".
- `nfc/TagWriter.kt` is the seam; `TagWriters.create` returns `Ntag424TagWriter`, a thin
  `IsoDep` adapter over `nfc/ChipSequence.kt`, which holds the whole chip sequence and has no
  Android types (it takes a `(ByteArray) -> ByteArray` transceiver and two server lambdas).

Keys arrive from the server per write, live only in memory for that write, and are never logged
or shown.

## Verify tag

Opened from the website with `pina-admin://verify-tag` (any tag) or
`pina-admin://verify-tag/<tagId>` (from a tag's page, so the app can say whether the chip is that
tag). "Hold the tag to the back of the phone." No keys are involved anywhere in this feature, so
the screen isn't `FLAG_SECURE`; the URL read from the chip (with its real `e=` and `c=`) is shown.

**The chip read** (`nfc/TagVerifier.kt`, pure core: transceiver lambda in, URL out). No
authentication and no key, exactly as a customer's phone reads the tag:

| # | APDU | Expect |
|---|---|---|
| 1 | `00 A4 04 0C 07 D2760000850101 00`: ISOSelectFile, NDEF application by DF name (AN12196 Table 22) | `90 00`, else "Is this an NTAG 424 DNA?" |
| 2 | `90 AD 00 00 07 02 000000 000100 00`: ReadData file 02, plain, offset 0, length 256 (3-byte LE) | data + `91 00`; `91 AF` → `90 AF 00 00 00` for the next frame |

**Exactly one ReadData per tap.** Every plain read of an SDM file increments the chip's
SDMReadCtr and yields a fresh `e=`/`c=`, so reading NLEN first and then the record would burn two
counter values. Instead the whole file (256 bytes on an NTAG 424 DNA) is read once, following
`91 AF` continuation frames (part of the same ReadData, not a new read), and NLEN plus the URI
record are parsed with `NdefUri`. Reader mode skips Android's own NDEF check, so the OS doesn't read
the file (and bump the counter) behind the app's back. NLEN 0, no data, or a record that isn't a
URI → "This chip is blank or has no URL."

**The server check.** `POST {base}/admin/api/tags/verify` with `{ url, tagId? }` (tagId omitted when
absent) and the session cookie:

| Response | Shown |
|---|---|
| 200 `{ ok, tag: { id, code, label, shopName, encodedAt }, uid, counter, lastCounter, fresh, uidMatches, tagMatches }` | **Tag verified** when `ok && fresh && uidMatches && tagMatches`; otherwise **Verified, but…** with each reason: "counter N was already seen, server has M", "chip UID X isn't the one bound to this tag", "this chip is tag CODE, not the one you opened". Then tag code, shop, label, written-on date, UID, chip counter, server counter, and the URL. |
| 401 / 3xx | Sign in on the website first. |
| 400 `bad_cmac` | The signature didn't verify: this chip wasn't written with Piña's keys. |
| 400 `malformed` | The server couldn't read this chip's URL (malformed). It may not be a Piña tag. |
| 404 `unknown_tag` | No tag has the code in this chip's URL (CODE). |
| 409 `unsigned` | This is a demo tag (unsigned): it has no signature to verify. |
| 503 `keys_missing` | The server has no NFC keys configured. |

Reader mode stays on after a result or an error: holding another tag (or the same one again) to
the phone starts a new check, and **Scan another** clears the card. **Done** goes back to the web
view and reloads the page. The chip is closed right after its one read, before the server call.

## The chip sequence (`nfc/ChipSequence.kt`)

Per guide §3, with state detection so a half-written chip can simply be held again:

| Step | What happens |
|---|---|
| Read UID | ISO-DEP tag id if it is 7 bytes and doesn't start `08`. Otherwise (Random ID) the UID is read with GetCardUid after authenticating with the factory key 0; a Random-ID chip without the factory key is refused. |
| Fetched | `POST …/personalise` with the UID. |
| Authenticated | ISOSelectFile by DF name `D2760000850101` (status checked), GetKeyVersion 0/1/2 unauthenticated (falls back to after auth if the chip refuses), AuthenticateEV2First key 0 trying `key0Candidates`: when GetKeyVersion says key 0 is at the target version, Piña's key(s) first and the factory zeros last; at version 0, the factory key first; otherwise (or unknown) the server's order. This keeps a rewrite from adding a failed authentication to the chip's counters (AN12196 §6.4) every time. `91 AE` on every candidate → "This chip's key 0 is not factory or Piña's; it cannot be written." `91 AD` → wait and retry. |
| URL written | GetFileSettings file 02 (CommMode.MAC in the session, response MAC checked), then WriteData file 02, offset 0, the server's `ndefFileHex` (1..239 bytes, one APDU) in **the file's own CommMode**, as the datasheet requires: plain (`90 8D 00 00 Lc 02 000000 len data 00`), MAC (plain data + MACt) or full (padded, encrypted, MACed). Always inside the key 0 session. A factory file 02 is plain with free write, and after our ChangeFileSettings it is plain with write key 0, so on real chips this is the plain path. Plain commands in the session still advance CmdCtr (the library's `nxpPlainCommand` does, and AN12196 session B shows it: Table 24's plain WriteData takes the counter from 1 to 2 before Table 25). Always redone. |
| SDM set | ChangeFileSettings file 02. The data field is built from the server's hex fields and cross-checked against the library's `FileSettings.encodeToData()`; any difference stops the write. Always redone. |
| Key 1, key 2 | ChangeKey (old key = zeros) when the slot is at version 0; skipped (DONE) at the target version; any other version is refused (old key unknown). |
| Key 0 | Changed last unless we authenticated with Piña's key 0 and it is already at the target version. Ends the session. |
| Verified | Re-select (drops auth so SDM mirrors), ReadData file 02 plain for the NDEF length, parse the URI record, check it is the written URL (same path, same length), `POST …/personalised`. |

Failures are thrown as `TagWriteException(step, message)`; messages carry at most a status word,
never keys or APDUs. The library's logger is never set. Key byte arrays are zeroed in `finally`
(the server's hex strings and the library's `SecretKeySpec` copies can't be; they are dropped).

Where it deliberately bypasses the library (`ChipCommands`):

- **Padding.** `AESEncryptionMode.encryptData` pads only when the data isn't a multiple of 16
  bytes, but the chip always wants ISO/IEC 9797-1 method 2 padding. AN12196 Table 17 writes
  128 bytes as 144 encrypted bytes; the library's `WriteData` sends 128 and would be refused (a
  unit test pins this). Full-mode WriteData and ChangeFileSettings are sent with the data pre-padded.
- **GetFileSettings.** We read only the CommMode bits of FileOption; the library's
  `FileSettings.decodeFromData` also parses SDM fields and reads the SDMAccessRights bytes in the
  opposite order to its own encoder. A response too short to carry a MAC is refused (the library
  skips the MAC check below 8 bytes).
- **ChangeKey for key 0.** The library's `ChangeKey.run(…, 0, …)` follows a successful change with
  `restartSession()`, which re-authenticates with the *old* key 0: that always fails on the chip,
  adds to its failed-authentication counter, is silently ignored (the boolean is discarded), and a
  tag lost at that moment raises an IOException indistinguishable from the ChangeKey failing. We
  send the one `C4 00` command (new key ‖ version, encrypted) ourselves, check `91 00`, and mark the
  session ended. Keys 1 and 2 use the library's `ChangeKey.run` as is.

## Tests

`./gradlew testDebugUnitTest` (plain JVM, JUnit 4, no Robolectric). Vectors are from AN12196
rev 2.0 (`app/src/test/.../An12196.kt`). The library's RndA comes from the public static
`ByteUtil.random`, which the tests replace with a fixed RndA, so authentication is replayed exactly.

- **Byte for byte against AN12196:** select (Table 22) and AuthenticateEV2First (Table 14) through
  the full `ChipSequence`; through `ChipCommands` directly: GetFileSettings in MAC mode (Table 7,
  including its response MAC), WriteData full (Table 17), WriteData plain (Table 24) followed by
  the library's ChangeKey key 2 (Table 25, including its response MAC) to pin that the plain
  command advances CmdCtr, and our ChangeKey key 0 (Table 26), on sessions rebuilt from Tables
  14 and 19/23; GetCardUid (Table 28). A `FakeTransceiver` asserts each command APDU and replays
  the table's response.
- **Checked by an independent implementation:** in the full sequence, GetFileSettings takes CmdCtr
  0 of the Table 14 session, so WriteData, ChangeFileSettings and ChangeKey 1/2/0 run at counters
  the application note has no vectors for. They (in plain, MAC and full WriteData variants) and the
  Random-ID path are checked by `RefSession`, a separate javax.crypto implementation (own AES-CMAC, session keys, IVs,
  JAMCRC) that is itself pinned to Tables 14, 17, 18, 23, 25 and 26. It verifies each command's
  MAC, decrypts and compares the payload, and returns correctly MACed responses.
- **Structure only:** GetKeyVersion and the final plain ReadData have exact APDUs from the
  datasheet framing but synthetic responses.
- **Verify** (`TagVerifierTest`): the exact select and single 256-byte ReadData APDUs, a replayed
  NDEF file with `e`/`c` filled (AN12196's file, and a Piña-shaped one), `91 AF` continuation
  frames (still one ReadData), blank chips (all zeros, NLEN 0 with leftovers, no data), a non-URI
  record, and refused select / ReadData. `VerifyVerdictTest`: the verdict line and reasons, and the
  error messages. `AdminApiTest`: `verify` request body and path (with and without tagId), success
  parsing, and each error code (401, malformed, bad_cmac, unknown_tag, unsigned, keys_missing).
- Also: NDEF read-back parsing, the SDM data field vs the server's hex (and Table 18), key-version
  skip logic, key 0 candidate order (derived first at the target version, factory first at 0),
  error mapping (wrong key 0, `91 AD`, status errors, mismatched read-back).

Not covered by tests, needs a real chip (guide §7): that the chip accepts unauthenticated
GetKeyVersion, that SDM mirrors on the plain ReadData after re-select, whether a 256-byte ReadData
comes back in one frame or with `91 AF`, and timing over NFC.
