# Music Box K — Privacy Policy

Version 1.0.0. Last reviewed 4 November 2026.

## The short version

The developer collects nothing about you. There is no account, no sign-up, no analytics, no advertising and no crash reporting, and no server belonging to the developer that the app talks to. What the app stores, it stores on your phone. Where it contacts another service, that is listed below and happens because you asked for it.

## Where it comes from

Music Box K is the version of Music Box offered in Mudita's store, with music on the phone and card, your own server and internet radio, a fork of CalmMusic by David Ray Wilson (GPL-3.0), rebuilt on Mudita's public MMD library.

## What the app stores, and where

On the phone, in the app's own private storage: the library index, playlists, the queue, a list of the last week's downloads, the names you give songs, albums and artists, the songs you heart, and settings. These never leave the phone except as described under backups below.

**A music server's address, user name and password.** If you add a Navidrome or other Subsonic server, its address and user name are kept in the app's settings file, and the password is sealed with an AES-GCM key generated in the phone's hardware-backed keystore (a key that cannot be exported) before it is written. If the key is lost, as after a restore onto another phone, the password cannot be read back and the app asks again. Usually the password is not sent: each request carries a salted hash of it (Subsonic's token sign-in). A server that does not accept that, such as Nextcloud Music, is sent the password itself, hex-encoded and not encrypted, with each request, as its protocol requires; Music Box K says so on the server page. Use https for such a server. The developer runs no server and receives nothing.

**A Jellyfin server's address, user name and sign-in.** If you add a Jellyfin server instead, the password is sent once, to the server's `/Users/AuthenticateByName`, and is not kept anywhere. The server hands back an access token, which is sealed with the same keystore key and kept in its place, with the address, the user name and the account's id. Every later request to the server carries that token, in a header or, in the address of each song so the player can open it, as a query parameter. The app also sends the server a name for itself, made up of "Music Box", the phone's model, the app's version and a random id made once on this phone, which is how Jellyfin lists the sign-in on its device page. Forgetting the server ends the sign-in on the server and deletes the token here. The developer runs no server and receives nothing.

**Downloaded songs.** Songs you download from your music server are saved as ordinary music files in Music/Music Box K, on the memory card if there is one (on Android 9 and 10, in the app's own storage instead). They stay there if the app is uninstalled, and other apps on the phone can see them. Music files you put on the phone yourself are left alone.

**Backups.** The app allows Android's backup (`allowBackup="true"`). Android may therefore copy the library database and the preferences into your Google backup, or to a new phone when you transfer, as your phone's backup settings decide; the app's own files folder is included in a device-to-device transfer and, on Android 11 and older, in a full backup (songs saved in Music/Music Box are not in that folder, except on Android 9 and 10). The settings file, which holds the server password or Jellyfin sign-in, is excluded from all of these. The developer holds no backup of any of it.

Uninstalling the app deletes everything in its private storage. There is no cloud copy and no copy held by the developer.

## Permissions, and what each is for

From `app/src/main/AndroidManifest.xml`, plus one that the libraries add when the app is built:

- `INTERNET`: for radio and your music server.
- `ACCESS_NETWORK_STATE`: added by a library, not listed in the manifest itself; the app uses it to know whether the phone is online.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: so music keeps playing with the screen off.
- `FOREGROUND_SERVICE_DATA_SYNC`: the kind of service the downloader is. It runs only while songs you asked for are arriving, with a notification and a Stop button.
- `WAKE_LOCK`: so playback and downloads are not interrupted.
- `POST_NOTIFICATIONS`: the player and download notifications.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: so the system does not stop playback.
- `READ_MEDIA_AUDIO`, and `READ_EXTERNAL_STORAGE` on Android 12 and older (which is what the Kompakt runs): asked for only when another app hands over a music file by its path rather than sharing it, so the app can play it. A file shared from another app comes with that app's permission to read that one file. You can refuse.

The app's listing also shows `com.wanderwildwood.jimeikin.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` because it reads the APK. AndroidX declares it for its own internal receivers; it is signature-level and namespaced to this app, so nothing else can hold it and it grants no access to anything on the phone.

The manifest also names the FM radio apps that may be on the phone (`com.android.fmradio`, `com.mediatek.fmradio`, `com.caf.fmradio`), so the app can offer to open one; it reads nothing from them.

Cleartext http is permitted (network security config), because a music server on a home network is usually served over plain http and many radio stations stream over nothing else. An address you type without a scheme is taken as https; plain http is used only when you type `http://` yourself, and for a server or station that is not on a home network the screen says that what is sent can be read on the way. Anything fetched over http can be read by others on that network.

Nothing a permission grants access to is sent anywhere, except as described in the next section.

## Lock-screen controls (optional)

Lock-screen controls are an accessibility service, off until you turn them on in Android's Accessibility settings. Only an accessibility service may draw above the Kompakt's lock screen, so that is what this has to be. It is limited in its configuration to one package, the system UI (`com.android.systemui`), and draws the app's play, pause, skip and stop buttons at the foot of the lock screen while something plays, standing aside for the PIN field and for anything another app shows over the lock screen. It reads no other app's content, and nothing it sees leaves the phone.

## Who else receives anything

Yes, where you ask for it. Each service is contacted only when that feature is used and operates under its own terms. No analytics, advertising or crash-reporting service is contacted at any point.

Hosts contacted:

- **radio.garden** (`radio.garden`): when you browse or play a radio station.
- **Radio Browser** (`de1.api.radio-browser.info`, `de2.api.radio-browser.info`, `all.api.radio-browser.info`): only when a radio search finds nothing on radio.garden, or radio.garden cannot be reached. What you typed in the search is sent, with the app's name and version. Nothing is sent about what you play.
- **A station you add by its address**: when you add it (it is played once, silently, to check it) and whenever you play it. Its audio comes from the broadcaster's own stream server.
- **api.zippopotam.us**: when you type a US ZIP code or a Canadian postal code into the radio search, to turn it into a place. Only what you typed is sent.
- **Your own music server**: whichever Navidrome or other Subsonic server, or Jellyfin server, you configure, at the address you type, and no other host. Each request carries your sign-in to it: a salted hash of the password for Subsonic (or the password itself for a server that asks for it, as above), the access token for Jellyfin. Jellyfin is sent the password once, when you connect.

On the phone: with Glance (`com.wanderwildwood.hitome`, the author's lock-screen app) installed, and Settings, "Show what is playing in Glance", turned on (it is off to begin with), the song playing is handed to Glance, and only to Glance, to show on the lock screen, where anyone holding the phone can read it.

In About, "feed the llamas" opens a donation page (square.link), wanderthe.dev opens the author's website and the source code link opens this app's page on GitHub, in your browser and only when you press them. The app does not fetch any of these pages and learns nothing about whether you went.

No data is sold, rented, or shared for advertising. There is no profiling, no personalization, and no automated decision-making about you.

## Legal basis (EU / EEA and UK)

Processing on the device is carried out for your own purposes and at your own direction. Where the app contacts an outside service, that request is made at your request and is necessary to provide the feature you asked for (Article 6(1)(b) GDPR); that operator is an independent controller for what it then does, under its own policy.

## Children

The app is suitable for all ages and does not knowingly collect anything from anyone, of any age, because the developer does not collect anything.

<!-- shared-about privacy section -->
## Who we are

Music Box K is made by wander wildwood, an independent developer: one person, not a company. wander wildwood is the data controller for anything the app itself handles, and that is very little. What you put in Music Box K stays on your phone. The only things that go out are the requests described above: requests for stations and their streams to radio.garden; the words of a radio search to Radio Browser; a ZIP or postal code to Zippopotam.us; and requests to your own music server (Navidrome, Subsonic or Jellyfin), if you add one. Nothing is sent to the developer, so the developer holds no data about you.

## How to contact us

Write to email@wanderthe.dev about your data, this policy or anything else. The same address is in the app, under About.

## Your rights

If you are in the EU, the EEA or the UK, the GDPR gives you the right to:

- see the personal data held about you (access)
- have it corrected (rectification) or deleted (erasure)
- limit how it is used (restriction)
- object to its use (objection)
- get a copy to take elsewhere (portability)
- withdraw your consent at any time, where something rests on it
- complain to a data protection supervisory authority, for example the one where you live

Similar laws elsewhere, such as the CCPA in California, give you the right to know, delete and correct, and to opt out of the sale or sharing of your data. Nothing from Music Box K is sold or shared.

Since Music Box K keeps what it stores only on your phone, you can exercise most of these rights yourself: remove songs, playlists and stations in the app, save your playlists and stations with Save a backup, delete downloaded songs in Music/Music Box K (ordinary files that stay after an uninstall), clear the app's data in Android's settings, or uninstall the app, which deletes all of it. For anything you cannot do yourself, write to the address above.

Music Box K connects to radio.garden, Radio Browser and Zippopotam.us. They are separate from this app and from its developer, and what they receive is covered by their own privacy policies; ask them about what they hold.
If you point Music Box K at a server of your own, what that server keeps is yours to manage.
<!-- /shared-about privacy section -->

## Changes

If a later version changes what the app stores or which hosts it contacts, this policy is updated in the same release and the change is described in the release notes. The source is public, so any claim here can be checked against the code: <https://github.com/wanderwildwood/jimeikin>. GitHub reports its licence as GPL-3.0.
