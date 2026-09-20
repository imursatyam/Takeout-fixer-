# Takeout Restorer 📸✨

> **Restore original dates, GPS locations, and EXIF metadata from Google Takeout exports directly on your Android device — no computer required.**

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Instagram](https://img.shields.io/badge/Instagram-@imursatyam-E4405F?logo=instagram&logoColor=white)](https://instagram.com/imursatyam)

---

## 📌 The Problem

When downloading your photo library from **Google Takeout**, Google separates the metadata:
- Media files lose their original capture dates, showing today's download date instead.
- Camera details, captions, and GPS tags are dumped into messy `.json` sidecar files (e.g., `IMG_1234.jpg.json`).
- Gallery apps (Google Photos, Samsung Gallery, Apple Photos) display your memories out of order in an unorganized clump.

**Takeout Restorer** fixes this completely on-device. It reads your Takeout `.zip` archives, pairs each photo/video with its corresponding `.json` sidecar, embeds the metadata back into the files, and updates filesystem & Android MediaStore timestamps.

---

## 🚀 Key Features

- ⚡ **Direct ZIP Processing**: Select one or multiple Google Takeout `.zip` files directly—no computer or manual unzipping needed.
- 🕒 **True Chronological Restoration**:
  - **Photos (JPEG, PNG, WebP)**: Writes EXIF tags (`DateTimeOriginal`, `DateTimeDigitized`, `GPSLatitude`, `GPSLongitude`, `ImageDescription`).
  - **Videos (MP4, MOV, 3GP, M4V)**: Patches QuickTime/ISO BMFF box headers (`mvhd`, `tkhd`, `mdhd` creation and modification timestamps).
  - **Filesystem Timestamps**: Multi-tiered timestamp restoration (`BasicFileAttributeView`, native `utimensat`, `futimens`, and `setLastModified`).
  - **Android MediaStore Sync**: Notifies `MediaStore.Images.Media.DATE_TAKEN` and `DATE_MODIFIED`, so galleries immediately sort chronologically.
- 🧠 **Smart Sidecar Matching**: Resolves truncated filenames, duplicated indices (`IMG_1234(1).jpg`), and `-edited` versions.
- 🔄 **2-Pass Resumable Engine**:
  - **Pass 1**: Rapidly indexes all JSON metadata into an on-device Room SQLite database.
  - **Pass 2**: Streams media files directly to the destination folder while embedding metadata.
  - Crash-proof & cancellable: Resumes right where it left off if interrupted.
- 🔋 **Background Processing**: Runs as an Android Foreground Service with live progress notifications; you can switch apps or lock your screen.
- 📁 **Organized Folders**: Option to preserve Google Photos album folder hierarchy or consolidate into a single clean target directory.
- 🔒 **100% Offline & Private**: Zero internet permissions used; all processing occurs strictly on your device.

---

## 🛠️ Step-by-Step: How to Fix Google Takeout

### Step 1: Export Your Photos from Google Takeout
1. Go to [takeout.google.com](https://takeout.google.com).
2. Click **Deselect all**, then scroll down and check **Google Photos**.
3. (Optional) Click **All photo albums included** to choose specific years or albums.
4. Click **Next step**, choose `.zip` format (we recommend 2GB, 4GB, or 10GB archive sizes), and click **Create export**.
5. Once ready, download the `.zip` files directly to your Android device (e.g. into your `Downloads` folder).

### Step 2: Restore with Takeout Restorer
1. Open **Takeout Restorer** on your phone.
2. Tap **Select Takeout ZIPs** and select all downloaded `.zip` parts (`takeout-*.zip`).
3. Tap **Select Destination Folder** and choose where to save your restored photos/videos (e.g. `DCIM/Restored_Photos` or `Pictures`).
4. *(Recommended)* Enable **Speed & Date Boost** (*All Files Access*) if prompted for 10x faster extraction and full filesystem timestamp accuracy.
5. Tap **Start Restoration Job**.
6. Let the app process. You can monitor progress and live logs, or minimize the app and let it run in the background.
7. Open your favorite gallery app (Google Photos, Samsung Gallery) — your photos will now be organized in their original capture order!

---

## 🏗️ Architecture & Tech Stack

- **UI**: Modern Jetpack Compose with Material Design 3.
- **Local Database**: Room Database (SQLite) for fast metadata indexing and resumable state tracking.
- **Background Execution**: Android Foreground Service with continuous Notification channel updates.
- **Metadata Engines**:
  - `androidx.exifinterface:exifinterface` for image EXIF embedding.
  - Custom binary atom parser & patcher for ISO/IEC 14496-12 MP4 boxes (`mvhd`, `tkhd`, `mdhd`).
- **Storage**: Storage Access Framework (SAF) + Direct File I/O fast-path.

---

## 👤 Developer & Contact

Developed with ❤️ by **Satyam**.

- **Instagram**: [@imursatyam](https://instagram.com/imursatyam)
- Feel free to reach out on Instagram for feedback, feature requests, or queries!

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
