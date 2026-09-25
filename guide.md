# Piña Admin: Android app for admin.mypina.co.uk, with a native tag writer

Plan for a small Android app, built in its own repository, that wraps the
admin website and adds the one thing a website cannot do: personalise an
NTAG 424 DNA tag in one go, writing the URL, switching on Secure Dynamic
Messaging (SDM) and changing the chip's keys. NXP TagWriter can write the URL
and SDM settings but cannot change keys; this app needs no hardware beyond an
Android phone with NFC.

Written 2026-09-25. Read alongside `CLAUDE.md` (NFC section) and
`apps/app/src/nfc/` in the Piña repo. The two workstreams below are in
different repositories; §4 is the seam between them.

## 1. What the app is

- **Everything on admin.mypina.co.uk, in a web view.** Sign-in, shops,
  approvals, tags, moving and deleting tags, scan rate. The website is the
  product; the app adds nothing to those pages and never re-implements them.
  New admin features land on the website and appear in the app for free.
- **One native screen: Write tag.** Opened from the Write this tag panel on a
  tag's page when that page is inside the app. With a blank or previously
  written chip held to the phone, it:
  1. Reads the chip's 7-byte UID.
  2. Fetches from the server everything for this chip: the NDEF file bytes,
     the SDM file settings, and the three keys (slots 0, 1 and 2).
  3. Authenticates with key 0 (factory zeros on a new chip, Piña's derived
     key 0 on a chip written before).
  4. Writes the NDEF file, sets the SDM file settings, changes keys 1, 2, 0.
  5. Reads the file back, which makes the chip produce a real tap URL, and
     sends it to the server. The server verifies it, binds the UID to the tag
     row, resets the tag's counter, and records that the tag is encoded.
  6. Shows the result, then returns to the tag's page, reloaded.

Also handled: a chip written before (same keys, or being moved to another
tag row), and a half-written chip (§3, state detection).

Out of scope: any native version of the website's pages, anything for
merchants or customers, Play Store distribution.

## 2. Decisions

**Web view for the site, native only for NFC.** A `WebView` pointed at
`https://admin.mypina.co.uk` with JavaScript and cookies on. The website's
own sign-in sets the 30-day `pina_a` cookie inside the web view, so the app
stores no admin key of its own. The native writer authenticates its API calls
with that same cookie, read from the web view's `CookieManager`. When the
session lapses, the site redirects to its sign-in page as usual and the
native call returns a redirect, which the app treats as "sign in again".

**The website tells the app when to go native.** The app sets a User-Agent
suffix, `PinaAdmin/<version>`. When the Write this tag panel is rendered for
that agent, it shows a **Write with this phone** button linking to
`pina-admin://write-tag/<tagId>`, in place of the TagWriter instructions. The
web view intercepts that scheme (`shouldOverrideUrlLoading`) and opens the
Write screen. Any other agent never sees the link. No JavaScript bridge.

**Keys travel to the phone; the master key never does.** The server derives
each chip's keys from the UID (AN10922, `UID || "PINA" || keyNo`, see
`apps/app/src/nfc/keys.ts`) and returns them over HTTPS to the
authenticated app, which uses them once and drops them. This is the same
exposure as today's admin page, which already shows the per-chip keys and the
shared key 1 as text and QR codes. Relaying every APDU to the server so the
crypto runs there would keep keys server-only but needs a multi-round
stateful protocol implemented in TypeScript from the datasheet. Not worth it
for a one-person tool.

**Chip protocol via an existing library.** Use
[johnnyb/ntag424-java](https://github.com/johnnyb/ntag424-java) (Java, MIT).
It implements AuthenticateEV2 (AES), WriteData, ReadData, GetFileSettings,
ChangeFileSettings with SDM settings, ChangeKey, GetKeyVersion, GetCardUid and
IsoSelectFile, and it talks to the chip through a transceiver lambda that wraps
Android's `IsoDep.transceive`. Its README says the 1.0.x API is not stable, so
pin a commit and build the jar into `app/libs/`; do not depend on a moving
version. Do not use its key-diversification helper: it uses a different
derivation from Piña's. Keys always come from the server as raw bytes.

**The server builds the bytes.** The app never computes offsets or NDEF
framing. `apps/app/src/nfc/personalise.ts` already has `sdmLayout()` and
`tagKeys()`; add `ndefFileBytes()` there and return everything from one
endpoint (§4). The app only has to drive the chip.

**Key 0 is per chip too**, derived with key number 0, as `personalise.ts`
already does. Keys 3 and 4 are left at factory values in v1 (they guard
nothing Piña uses); note this as a follow-up.

**Rewriting a chip resets its tap counter**, and the server refuses counters
it has seen. So the "personalised" endpoint resets `tag.last_counter` to 0
before verifying the first read. This is admin-only and logged.

## 3. Chip protocol, with references

All commands and worked byte examples are in NXP AN12196 rev 2.0
(https://www.nxp.com/docs/en/application-note/AN12196.pdf). Section numbers
below are from that revision. The library does the framing and crypto; these
are here so the sequence can be checked and so unit tests have vectors.

Transport: ISO 7816 APDUs over ISO-DEP. Native commands are wrapped as
`90 <INS> 00 00 <Lc> <data> 00`; status `91 00` is success, `91 AF` means
more frames follow.

| Step | Command | AN12196 | Notes |
|---|---|---|---|
| Select the NDEF application | ISOSelectFile by DF name `D2760000850101` | §5.3, Table 22 | `00 A4 04 0C 07 D2760000850101 00` |
| Get the UID | From `IsoDep` tag id, or GetCardUid after auth | §6.3 | Random ID is off from the factory; if the tag id is 4 bytes or starts `08`, use GetCardUid. |
| Authenticate | AuthenticateEV2First, key 0 | §5.6, Table 14 | Session keys from SV1/SV2 (`A55A…`/`5AA5…`), TI, CmdCtr. Table 14 is a full vector with key 0 = zeros. |
| Write the NDEF file | WriteData file 02, CommMode.FULL | §5.8.2, Table 17 | Do this authenticated and in full mode so it works whether or not the file has been locked before. Bytes come from the server. |
| Set SDM on the file | ChangeFileSettings file 02 | §5.9, Table 18 | Our data field is `40 00E0 C1 FF12 <PICCDataOffset> <SDMMACInputOffset> <SDMMACOffset>`, offsets 3 bytes little-endian. See below. |
| Change key 1 | ChangeKey, key ≠ auth key | §5.16.1, Table 25 | Data = (new ⊕ old) ‖ version `01` ‖ CRC32(new), encrypted. Table 25 is a full vector. |
| Change key 2 | same | §5.16.1 | |
| Change key 0 | ChangeKey, key = auth key | §5.16.2, Table 26 | Data = new ‖ version `01`, encrypted. Ends the session. Do this last. |
| Read back | ReadData file 02 (plain, after re-select) | §3 | The read increments the SDM counter and mirrors `e` and `c` into the data. Parse the URL out of the NDEF record. |

Our ChangeFileSettings data field, explained (compare Table 18, which uses
different access rights):

| Bytes | Meaning |
|---|---|
| `40` | FileOption: SDM and mirroring on, CommMode plain for reads |
| `00 E0` | AccessRights: ReadWrite 0, Change 0, Read E (free), Write 0 |
| `C1` | SDMOptions: UID mirror, SDMReadCtr, ASCII; no counter limit, no encrypted file data |
| `FF 12` | SDMAccessRights: RFU F, SDMCtrRet F (no one), SDMMetaRead 1, SDMFileRead 2 |
| `20 00 00` | PICCDataOffset (for `https://mypina.co.uk/t/XXXXXXX?e=`) |
| `43 00 00` | SDMMACInputOffset |
| `43 00 00` | SDMMACOffset |

The offsets depend on the tag code length and origin; take them from the
server, never hard-code. The datasheet's field order in ChangeFileSettings is
PICCDataOffset, SDMMACInputOffset, SDMMACOffset when only those three apply;
the library's `SDMSettings` should take care of it.

Detecting a chip's state before writing (so a half-finished chip can be
finished): GetKeyVersion for keys 0, 1, 2 returns version `00` for factory
keys and `01` for Piña's. Check whether the library requires authentication
for it; if it does, authenticate with factory key 0 first, and if that fails
authenticate with derived key 0. Skip ChangeKey for any slot already at
version `01`. The NDEF write and ChangeFileSettings are always redone; they
are idempotent.

## 4. Server side (Piña repo, this workspace)

**Two JSON endpoints** under `/admin/api/`, so the existing host routing
serves them only on admin.mypina.co.uk (and localhost in dev). Each handler
checks `isAdmin()` from `src/admin/session.ts`, which already accepts the
`pina_a` cookie or an `x-admin-key` header. Keys in responses are uppercase
hex.

```
POST /admin/api/tags/{tagId}/personalise      body { uid }
     → {
         tag: { id, code, label, shopName },
         url,                       "https://mypina.co.uk/t/CODE?e=<32 zeros>&c=<16 zeros>"
         ndefFileHex,               NLEN + URI record, what WriteData writes at offset 0
         sdm: { fileOption: "40", accessRights: "00E0", sdmOptions: "C1",
                sdmAccessRights: "FF12", piccDataOffset, sdmMacInputOffset,
                sdmMacOffset },      offsets as integers
         keys: { key0, key1, key2, keyVersion: 1 },
         key0Candidates: [ "000…000", key0 ]   try in order when authenticating
       }
     errors: 401 not signed in; 404 unknown tag; 409 { error: "uidTaken",
     tag, shop } when the UID already belongs to another tag row (the app
     says so and sends the person back to the website, where the tag can be
     moved); 409 { error: "notApproved" }.

POST /admin/api/tags/{tagId}/personalised     body { uid, url }
     → { ok: true, counter }        after: uid bound, last_counter reset then
                                    counter claimed, encoded_at set, key_version set
     errors: 400 { error: "bad_cmac" | "malformed" | "uidMismatch" }
```

**The Write this tag panel** (`src/app/admin/(app)/shops/[shopId]/write-tag.tsx`)
reads the request's User-Agent (from `headers()`). When it contains
`PinaAdmin/`, it renders a **Write with this phone** button linking to
`pina-admin://write-tag/<tagId>` and hides the TagWriter step list; the URL,
offsets, keys and QR codes stay as a fallback. It also shows `encoded_at`
("Written on …") on the tag card.

Other server work:

- `src/nfc/personalise.ts`: add `ndefFileBytes(url)` (NLEN ‖ `D1 01 <len> 55 04` ‖ URL without `https://`), unit-tested against AN12196 Table 15/16 (`0051D1014D5504…` for the NXP example). Reuse `sdmLayout` and `tagKeys`.
- `src/admin/tags.ts`: `personaliseTag(db, tagId, uid)` (validate, check UID uniqueness, derive) and `recordPersonalised(db, tagId, uid, url)` (reset counter, `verifySun`, `claim_tap_counter`, set `encoded_at`).
- Migration `0011_tag_encoded.sql`: `alter table tag add column encoded_at timestamptz;`. Regenerate `types.ts`.
- Route handlers under `src/app/admin/api/tags/[tagId]/…/route.ts`.
- `CLAUDE.md`: NFC section, admin routes, the app, the counter-reset rule.
- Tests: unit for `ndefFileBytes` and for the User-Agent switch; integration for the two admin functions (bind, reset, claim, `uidTaken`, `notApproved`); the existing `simulate.ts` produces a valid read-back URL for the `personalised` test.

Build the server side first and check it with `curl` (send `x-admin-key`)
and `pnpm tag:url` before the app exists.

## 5. The app (new repository)

Name: Piña Admin. Package `uk.co.mypina.admin`. Kotlin, Jetpack Compose,
single activity, minSdk 26. No analytics, no crash reporting in v1.

**Web view.** Loads `https://admin.mypina.co.uk/admin`. JavaScript and
cookies on, `CookieManager` persistent so the 30-day sign-in survives app
restarts. User-Agent = the default plus ` PinaAdmin/<versionName>`. The
system back button navigates the web view's history and only exits the app at
the root. Links off the admin host (`mypina.co.uk` receipts, `instagram.com`)
open in the phone's browser. Handles `pina-admin://write-tag/<tagId>` by
opening the Write screen; on return, reloads the page it came from.

**Write screen.** "Hold the tag to the back of the phone." A progress list as
steps complete (read UID, fetched, authenticated, URL written, SDM set,
key 1, key 2, key 0, verified), then a result card with tag code, shop, UID
and counter, and a Done button back to the web view. Keep the screen on and
the tag on the phone until Done shows. On any error, say which step failed
and that the chip can be held again to continue; the state detection in §3
makes that safe. API calls send the `pina_a` cookie taken from
`CookieManager.getCookie("https://admin.mypina.co.uk")`; a 401 or redirect
means "sign in on the website first".

NFC: use `NfcAdapter.enableReaderMode` with `FLAG_READER_NFC_A` and
`FLAG_READER_SKIP_NDEF_CHECK`, only while the Write screen is showing, so
Android does not open the tag's URL in the browser instead. `IsoDep.get(tag)`,
`connect()`, `setTimeout(5000)`. Run the whole chip sequence on one background
thread while the tag stays connected.

Secrets: keys live only in memory for the duration of one write. Never log
them, never put them in a `Toast`, and mark the Write screen `FLAG_SECURE`
so it can't be screenshotted. No admin key is stored by the app; the only
credential is the site's own session cookie. A debug build flag points the
app at `http://admin.localhost:3000` (cleartext allowed for that host only,
debug builds only); note that `admin.localhost` on a phone needs the Mac's
LAN address or a tunnel, so a hosts entry or `adb reverse tcp:3000 tcp:3000`
plus the plain `localhost:3000` host in dev.

Library: build `ntag424-java` from a pinned commit with `mvn package` and copy
the jar into `app/libs/`. Wire `DnaCommunicator.setTransceiver { iso.transceive(it) }`.
Sequence per §3, using `AESEncryptionMode.authenticateEV2(comm, 0, key)`,
`WriteData.run`, `GetFileSettings.run` then set `sdmSettings` and
`ChangeFileSettings.run`, `ChangeKey.run` for 1, 2, 0, then re-select and
`ReadData.run`. If `SDMSettings` cannot express our exact bytes, add a raw
`ChangeFileSettings` path that takes the data field from the server.

Distribution: a debug APK installed with `adb install`. Nothing goes to the
Play Store.

## 6. Tooling on the Mac

Nothing Android is installed yet (Java runtime and SDK missing; only `adb`
via Homebrew).

```
brew install --cask temurin@17          # JDK 17
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
brew install maven                      # to build ntag424-java
```

Set `ANDROID_HOME` (`/opt/homebrew/share/android-commandlinetools`) and
`JAVA_HOME`. Scaffold with the Gradle wrapper (no Android Studio needed):
`gradle init` is not enough for Android, so start from a minimal Compose
template or generate one with Android Studio once, then build with
`./gradlew assembleDebug` and install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
Enable USB debugging on the phone, or `adb pair` over Wi-Fi.

## 7. Testing

- **Unit, app:** the chip sequence against a fake transceiver that replays
  AN12196 Tables 14, 17, 18, 25 and 26 byte for byte (they share one session:
  key 0 zeros, TI `9D00C4DF` / `7614281A`). This proves the library is driven
  correctly before a real chip is touched.
- **Unit, server:** `ndefFileBytes` against AN12196 Table 15/16; the
  User-Agent switch; the two admin functions in an integration test.
- **Web view:** sign in, approve a shop, open a tag, confirm the Write with
  this phone button appears in the app and not in Chrome.
- **Real chip, staging:** point the app at the dev server, write a spare
  chip, and confirm the read-back URL verifies and `last_counter` is 1.
- **Real chip, production:** write a spare chip for the demo shop, tap it
  with an iPhone and an Android phone, confirm "waiting for your payment"
  and `Taps counted` on the admin page. Then rewrite the same chip to a
  second tag row to prove the counter reset and re-authentication with
  derived key 0 work. Then write the real trial tags.
- Keep one spare chip untouched until the sequence has succeeded twice.

## 8. Order of work

1. Piña repo: migration, `ndefFileBytes`, admin tag functions, two route
   handlers, the User-Agent switch on the panel, tests. Deploy.
2. Mac tooling; scaffold the app as a web view of the admin site with the
   User-Agent suffix and the custom scheme; confirm sign-in and the button.
3. Build the library jar; chip sequence against the fake transceiver with the
   AN12196 vectors.
4. Real chip against the dev server, then production, as in §7.
5. Update `CLAUDE.md`, `docs/backlog.md` (keys 3 and 4, LRP mode, a batch
   mode that writes several tags for one shop in a row) and the NFC memory.

## 9. Risks and unknowns

- **Library coverage.** `ChangeKey` for the currently authenticated key
  (Case 2, key 0) and `GetKeyVersion` without prior authentication are the
  two things to check in the library's source first. If either is missing,
  implement that one command from AN12196 (§5.16.2 is a full vector).
- **Library API drift.** Pinned commit and vendored jar avoid it.
- **Web view sign-in.** The sign-in form posts a Next.js server action;
  that works in a web view with JavaScript on. If Google ever blocks the
  embedded browser for OIDC, it doesn't matter here: admin sign-in is a key,
  not OIDC.
- **Random ID.** If a chip ever has Random ID enabled, the IsoDep tag id is
  not the UID; use GetCardUid after authentication (AN12196 §6.2, §6.3).
- **Half-written chips.** Covered by state detection (§3). The dangerous
  case is a chip whose key 0 was changed to something other than factory or
  Piña's derived key: it can't be recovered, so the app must never generate
  keys itself.
- **Counter reset abuse.** Only the admin API can reset a counter, and only
  as part of a verified read-back. Log it with tag id and old counter.
- **Wi-Fi or server outage mid-write.** All server calls happen before the
  chip sequence starts and after it ends; nothing is fetched while the chip
  is being written.
