# Maeil News Android app

Home-screen widget and app for the daily Korean news cards.

## How to open it
1. Open Android Studio. Choose **Open** and select this `android` folder.
2. Wait for it to finish syncing.
3. Connect your phone with a USB cable (turn on Developer options and USB debugging on the phone), then press **Run**.
   Or choose **Build → Build Bundle(s) / APK(s) → Build APK(s)** to get an installable file.

## Where the data comes from
The app reads `news.json` and `quiz.json` from the GitHub Pages site of this repository
(`BuildConfig.CONTENT_BASE_URL` in `app/build.gradle.kts`). It keeps a copy on the phone,
so the widget still shows the last news when you are offline.

## Widget
- Shows the English and Korean headline and the word translations of the Korean title.
- ‹ and › change the article. **Read** opens the summary popup (5 English sentences, the Korean
  sentences, and each Korean word). ⟳ fetches the latest news.
- **Save cards + video** appears only for Korean articles. Files go to Pictures/MaeilNews and Movies/MaeilNews.
- **Weekly quiz** saves the quiz cards and video.

## Not yet checked
This project has not been built on a phone yet. The first build may show small errors; send them and they get fixed.
