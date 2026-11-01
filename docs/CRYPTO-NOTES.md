# Cryptography notes, for a reviewer

A scan of the APK finds the strings `MD5`, `AES/CBC/PKCS5PADDING` and `AES/CBC/PKCS7Padding`.
This says where each comes from and why none of them protects anything of the reader's.

## What the app's own code uses

| Use | Algorithm | Where |
|---|---|---|
| Sealing the music-server password (and, in Music Box, the YouTube sign-in cookie) | AES-256-GCM, no padding, key generated in and never leaving the Android Keystore | `data/Secrets.kt` |
| Checking a copied download matches the original | SHA-256 | `data/PublicDownloads.kt` |
| Salt for the Subsonic sign-in | `SecureRandom`, 8 bytes | `data/SubsonicClient.kt` |

## MD5 in the Subsonic sign-in (`data/SubsonicClient.kt`, `md5`)

The Subsonic API (1.13.0 and later; Navidrome, Airsonic, Gonic and others) defines token
sign-in as `t = md5(password + salt)` with a random salt `s`, sent as `u`, `t`, `s`. The
server holds the password and computes the same thing; there is no other value it accepts.
MD5 here is a protocol requirement of the server, not a choice: it is not used to store
anything, to check integrity, or to sign anything. The point of the scheme is that the password
itself does not travel; the salt is fresh for each request. A server that does not accept
tokens (Nextcloud Music, LDAP-backed servers) is sent the password itself, hex-encoded
(`enc:`), and the app says so on the server page and tells the reader to use https. Addresses
typed without a scheme are https; plain http is used only when typed, with a warning for a host
that is not on a home network.

## CBC / PKCS5 / PKCS7 in AndroidX Media3

These strings come from `androidx.media3` and are not called by the app's code:

- `AES/CBC/PKCS7Padding` and `AES/CBC/PKCS5PADDING`: `Aes128DataSource` / `HlsMediaChunk`
  decrypt HLS segments. HLS (`.m3u8`) playlists may declare `#EXT-X-KEY:METHOD=AES-128`, which
  the HLS specification (RFC 8216) defines as AES-128 in CBC mode with PKCS7 padding. The app
  plays internet radio, and some stations (the BBC among them) are HLS; the add-station note
  says HLS streams play. Removing `media3-exoplayer-hls` would remove those stations.
- The decryption key is whatever the stream's own playlist names; it is a content-protection
  wrapper chosen by the station, not a protection of anything on the phone.
- OkHttp carries `MD5`/`HmacMD5` names (for the `Content-MD5` header and Digest auth
  challenge handling); the app does not use them.

Nothing the reader stores or types is protected by MD5, CBC, or PKCS padding; their password
is protected by AES-GCM in the Keystore (`Secrets.kt`).

## Music Box K

Music Box K is built without the YouTube code and library. The strings above are the same in
both apps; the full app additionally uses SHA-1 in the YouTube Music client
(`src/full/.../YouTubeMusicInnertubeClient.kt`, the `SAPISIDHASH` header YouTube requires).

## Plain http to the reader's own server

Music Box can be pointed at a music server on the reader's own network (Navidrome, Subsonic-compatible servers), and at internet-radio streams, some of which are http. Android refuses plain http by default, so `res/xml/network_security_config.xml` permits it; permitting
cleartext does not weaken https, it only stops Android refusing http. Android cannot allow it for a range of
addresses only, so it is allowed everywhere. An address typed without a scheme is https; plain http is used only when the reader types http://, and the app warns, with the host named, when that host is not on a home network (`PlainHttp.kt`). A scan that flags `cleartextTrafficPermitted` is seeing this
and not an accident: removing it would stop every home server that has no certificate from working.
