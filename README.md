# Vault Anything

A family document vault for Android. Create a family space, invite the others, and keep everyone's important documents in one safe place: passports and IDs, health results, school papers, the car's insurance, contracts, cards, and links to the sites that hold the rest.

- **Spaces** hold a family. Each member keeps their own documents in it.
- **Adults and children.** Adults see everyone's documents and look after the children's. Children see only their own. Whoever creates the space is an adult; whoever joins starts as a child until an adult in the space says otherwise, in the members list.
- **Documents** are a photo, a file of any kind (up to 25 MB) or a web link, with a title, a category (Identity, Health, Home, Car, School, Finance, Other), tags, a note and a date it stops being valid.
- **Expiring soon** sits on top of the family's page: everything that runs out within three months, soonest first.
- **Favorites** come first in the list, and have a filter of their own, like each category and each member.
- **Open, share, print.** A document opens in the app the phone uses for it, goes through the share sheet to email, messaging apps, nearby phones and printers, and pictures and PDFs print straight from the app.
- **Share into the vault.** "Share" a file, a picture or a link from any other app to Vault Anything, pick the family space, and it becomes a document.
- **Invites** work four ways: a code anyone can type, a QR code, a link, or an invitation for one email address (shown inside the app when that person signs in with it).
- **Leaving** a space, being removed from it or deleting the account deletes that person's documents in it. The family keeps nothing of someone who is gone.
- **Delete account** in Settings leaves every space (a space you own goes to the next adult, an empty one is deleted) and removes the sign-in. The Play Store requires this of apps with accounts.
- Sign-in is with Google, or with an email and password (with a new-password link and an email to confirm the address). Everything lives in the cloud, so a new phone shows the same documents after signing in. The app is in English and Greek. It follows the phone's language until another one is chosen on the **profile page**, which also has sign out.

Coming next, in this order: reminders before a document expires, scanning paper to PDF with text recognition and search; PIN locks on a document or on a whole set of documents, with a reset by email (a child's reset goes to a parent), and an activity log; expiring share links and offline copies; emergency access, a child turning 18 and choosing what the parents keep seeing.

## Set up Firebase (once, about fifteen minutes)

The app stores everything in a Firebase project that you own. Files need Cloud Storage and the clean-up needs Cloud Functions, so the project is on the **Blaze** plan, which asks for a payment card. A family-sized vault stays inside the free allowance, so the bill should be zero or a few cents a month.

1. Open the [Firebase console](https://console.firebase.google.com) and create a project. Give it the id `vault-anything` if the console lets you (press the pencil next to the suggested id); otherwise note the id it gives you, because step 8 needs it. Google Analytics can stay off.
2. Press **Upgrade** at the bottom left and choose **Blaze**.
3. Go to **Build > Authentication > Sign-in method** and enable **Google**. Pick a support email when asked. Then press **Add new provider** and enable **Email/Password** (leave "Email link" off). The emails Firebase sends sign with the project's public-facing name, which you set under **Project settings > General**.
4. Go to **Build > Firestore Database** and create a database in **production mode**. Choose a location close to you, for example `eur3 (Europe)`. The location cannot be changed later.
5. Go to **Build > Storage** and press **Get started**, in production mode, in the same location.
6. Go to **Project settings > Your apps**, add an **Android app**, and enter:
   - Package name: `com.kokouliss.vaultanything`
   - SHA-1: `5E:BF:9C:15:BD:EF:A2:D2:88:F0:B2:39:01:8C:A5:5A:48:5E:2A:E8`. The console calls this field optional, but Google sign-in does not work without it. To add it later, open **Project settings > Your apps**, pick the Android app and press **Add fingerprint**.
7. Download `google-services.json`. For builds on your own computer, put it in the `app` folder, so the file is `app/google-services.json` (it is ignored by git on purpose). For the builds GitHub makes, go to the repository's **Settings → Secrets and variables → Actions** and add a repository secret named `GOOGLE_SERVICES_JSON` with the whole content of the file. Do this after step 3, because the file must contain the Google sign-in client id.
8. Deploy the security rules, the clean-up functions and the invite page. On a computer with [Node.js](https://nodejs.org) 22 or newer, in this folder:

   ```
   npm install -g firebase-tools
   firebase login
   cd firebase
   npm --prefix functions install
   firebase deploy
   ```

   Without the rules the app cannot read or write anything. If the project id is not `vault-anything`, first change it in `firebase/.firebaserc`, and change the host `vault-anything.web.app` in `core/domain/.../Invites.kt` and `app/src/main/AndroidManifest.xml` to `<your id>.web.app`, so invite links open the app. Run `firebase deploy` again after each update that touches `firebase/`.

The SHA-1 belongs to the debug key in `app/debug.keystore`, which is committed so that every machine builds an app Google sign-in accepts. A release build signed with another key needs that key's SHA-1 added in step 6 as well.

If you choose a Google account and land back on the sign-in screen, the SHA-1 is missing or belongs to another key. Add it as described in step 6 and try again after a few minutes. The app does not need to be rebuilt.

Android checks the invite page when the app is installed, so install the app again after the first deploy. The page lists the SHA-256 of the shared debug key in `firebase/hosting/assetlinks.json`; an app signed with another key needs that key's SHA-256 added there.

## Download

Every pull request and every merge to `main` builds the app and puts it on a release page: a pre-release named `pr-<number>` for a pull request, replaced on every push, and a numbered release for each merge. Open the APK link on the phone, install, and allow installs from the browser when Android asks. The same link updates the app in place; the documents stay in the cloud. The build number on the release page is also the app's version, so a crash report in the Firebase console (Crashlytics) names the build it came from.

## Signing key (once, before the Play Store)

Until the steps below are done, downloads are signed with the shared debug key from `app/debug.keystore`, which anyone can read. That is fine for trying the app out. For a store listing, and so that nobody else can build an "update" that installs over the app, make a key of your own:

1. Make the key, on any computer with a JDK (Android Studio brings one in its `jbr/bin` folder). Pick two passwords and keep them:
   ```
   keytool -genkeypair -v -keystore vault-anything.keystore -alias vault-anything -keyalg RSA -keysize 2048 -validity 10000
   ```
2. In the GitHub repository go to **Settings → Secrets and variables → Actions** and add four repository secrets: `RELEASE_KEYSTORE_BASE64` (the file as text: `base64 -w0 vault-anything.keystore` on Linux, `base64 -i vault-anything.keystore` on a Mac), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`vault-anything`) and `RELEASE_KEY_PASSWORD`. From the next merge to `main` on, the release page carries the optimized release build signed with this key, and `vault-anything.aab`, the file you upload to Google Play.
3. Print the key's fingerprints with `keytool -list -v -keystore vault-anything.keystore -alias vault-anything`. Add the **SHA-1** in the Firebase console under Project settings → Your apps → Add fingerprint, or Google sign-in refuses the new build. Add the **SHA-256** to `firebase/hosting/assetlinks.json` and deploy hosting again, or invite links stop opening the app.
4. An app signed with a new key does not install over the old one. Everyone removes the app once and installs the new download.

Keep the keystore file and both passwords somewhere safe, such as a password manager. Without them the app can never be updated on the Play Store again.

## Build and run

Open the folder in a recent Android Studio and press **Run**, or build from a terminal with JDK 17 or newer:

```
./gradlew :app:assembleDebug
```

The app is then at `app/build/outputs/apk/debug/app-debug.apk`. It needs Android 9 or newer.

A build without `app/google-services.json` still works, but only shows a "Setup needed" screen.

## Tests

```
./gradlew testDebugUnitTest :core:domain:test   # unit tests
cd firebase && npm install && npm test          # security rules, on the local Firestore and Storage emulators
```

The rules tests need Node 20 or newer and Java 21 or newer. Run them before publishing a change to `firebase/firestore.rules` or `firebase/storage.rules`. GitHub runs all of them on every pull request.

## How it is built

Kotlin and Jetpack Compose, with Hilt, Navigation 3 and Firebase (Auth, Firestore, Cloud Storage and Cloud Functions).

| Module | What it holds |
| --- | --- |
| `app` | Start-up, sign-in gate, navigation, what the app is opened with (a shared file or link, an invite link) |
| `feature/auth` | Sign-in screen (Google, email and password) and the profile page |
| `feature/family` | Family spaces, members and their roles, invites, and the document list of a space |
| `feature/documents` | One document: viewing, opening, sharing and printing it, adding and editing, and the screen that asks which space a shared file goes to |
| `core/domain` | Models and repository interfaces, plain Kotlin |
| `core/data` | Firebase implementations of the repositories, thumbnails of pictures and PDFs |
| `core/ui` | Theme, icons and shared components |
| `build-logic` | Gradle convention plugins |
| `firebase` | Security rules and their tests, the clean-up functions, the invite link page |

The Firestore document layout is described at the top of `core/data/src/main/kotlin/com/kokouliss/vaultanything/core/data/firebase/FirestoreSchema.kt`. Files live in Cloud Storage at `spaces/{spaceId}/documents/{documentId}/file`; the Storage rules look the document up in Firestore before allowing a download, so children cannot fetch an adult's file even with its address.

## Known limits

- The people who run the Firebase project can read everything in it. The locks coming in a later version keep documents from other family members, not from the project's owner.
- A file is uploaded while the "Add document" screen is open, with a progress bar. Leaving the screen does not stop the upload, but a lost connection does: the document disappears again and the screen says so, so it can be added once more.
- Only pictures and PDFs print from the app. Other files open in the app that knows them, which usually prints too.
- Email invitations show up inside the app when the invited person signs in with that address. No email is sent.
- Deleting a space, a document or a member's documents on leaving is finished by a Cloud Function. Until `firebase deploy` has run, deleted spaces leave their documents and files stored where nobody can reach them.
- A document's file, kind, owner and link cannot be changed after it is added; its title, category, tags, note, date and favorite can. To replace a file, add the document again and delete the old one.
- An adult who changes another member's role could also change that member's name in the same write. The app never does; the rule cannot tell the two apart.
