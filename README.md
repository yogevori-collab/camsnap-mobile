# CamSnap for Android

A standalone phone app: it opens **Provision CAM2** on schedule, saves a snapshot of every camera you marked,
retries every few minutes while a camera has no video, and shows the snapshots, offline status and the
pumping-sheet charts. No PC needed.

Needs **Android 11 or newer**.

## 1. Build the app (once, on GitHub, nothing to install)

1. Sign in at https://github.com (free account), click **+ → New repository**, name it `camsnap-mobile`,
   choose **Public**, tick **Add a README file**, then **Create repository**.
   (Public lets you download the app on the phone without signing in. Choose Private if you prefer,
   then you download it while signed in.)
2. In the new repository click **Add file → Upload files**, and drag in **everything inside** the
   `CamSnapMobile` folder (including the `.github` folder). Click **Commit changes**.
3. Click the **Actions** tab. A build named "Build CamSnap APK" starts by itself and takes about 5 minutes.
   A green tick means it worked. (A red cross: open it, copy the error and send it to Claude.)
4. On the repository's main page, open **Releases** (right side) → newest build → **CamSnap.apk**.
   Open that page on the phone, or download it on the PC and copy it to the phone.

## 2. Install on the phone

1. Open `CamSnap.apk` on the phone. Android asks to allow installing from this source → **Allow**.
2. If Play Protect warns about an unknown app, choose **Install anyway** (it is your own build).

## 3. Set it up (Setup tab in the app)

1. **Accessibility settings** → *Installed apps* / *Downloaded apps* → **CamSnap camera snapshots** → On.
   On Android 13+ you may first need: Settings → Apps → CamSnap → ⋮ → **Allow restricted settings**.
2. **App battery settings** → Battery → **Unrestricted**.
3. Phone settings for a dedicated monitoring phone:
   - Screen lock → **None** (snapshots can't be taken while the lock screen is showing)
   - keep it on the **charger**, and Developer options → **Stay awake** = On
4. **CAM2 app**: choose Provision CAM2 from the list.
5. In CAM2, open the live view with the cameras you want (e.g. a 4-split). Back in CamSnap press
   **Mark cameras**: it opens CAM2, takes a screenshot and shows it. Drag a box over a camera picture and
   type its name, or drag over the whole video area and type `2x2` to split it. Choose the hours, press **Save**.
6. **Pumping sheet**: paste the Google Sheet link, **Save settings**.

Snapshots are stored in the app (shown on the Cameras tab) and, if ticked, also in the Gallery under
*Pictures/CamSnap/<camera>*.

## Good to know

- Keep the phone in the same orientation you used for marking (rotating it changes the screen size;
  the app will report it and you can mark again).
- When a snapshot is due, CamSnap brings CAM2 to the front for a few seconds.
- Uninstalling the app deletes its own copy of the snapshots; the Gallery copies stay.
