This is a Kotlin Multiplatform project targeting Android, Desktop (JVM), built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/).

- [/androidApp](./androidApp) contains the Android application.
- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/shared](./shared) holds the code shared across your applications — Compose UI, business logic, and platform-specific implementations. [src](./shared/src) is for common code; the sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the folder.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required. Build the whole project with `./kotlin build`.

### Running

Use the run configurations provided by the run widget in your IDE's toolbar. You can also run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- Desktop app: `./kotlin run -m desktopApp`

### Connecting to a server

Enter the HTTPS address of your Slopify server. Local Compose uses `http://127.0.0.1:8067` for production and `http://127.0.0.1:8068` for development. For an Android device connected over ADB, run `adb reverse tcp:8067 tcp:8067` or `adb reverse tcp:8068 tcp:8068` on the server host.

On a fresh server the app asks for a username, password and one-time setup code. Retrieve the code using the command in the [server setup instructions](../server/README.md#регистрация-первого-администратора).

After signing in, open an album and select a track to play it. The [server library instructions](../server/README.md#локальная-библиотека) show how to add a local test album.

Select **Поиск** in the library to find a track on YouTube. Use the play button over the thumbnail to listen directly from the search results. Select the download icon on the right to import the track into the shared library. YouTube search requires a proxy configured on the server. [Proxy setup](../server/README.md#поиск-и-загрузка-с-youtube)

### Invitations and offline listening

As an administrator, select **Пригласить** and share the one-time code. On the sign-in screen, the recipient selects **Регистрация по приглашению** and enters the code with their credentials.

Select **Скачать** beside a track to keep it on the device. Open **Скачанные треки** from the main screen to play or remove saved audio without connecting to a server. The app also caches streamed tracks automatically up to 256 MiB.

Use **Цвета** to set the background and accent colors in `#RRGGBB` format. The app keeps these settings between launches.

### Linux Desktop playback

Install GStreamer 1.x with the base playback plugins, the `souphttpsrc` HTTP source, an MP3 decoder such as `mpg123audiodec`, and an audio output plugin for your system. On Debian-based distributions these are available in `gstreamer1.0-plugins-base`, `gstreamer1.0-plugins-good`, and an audio output package such as `gstreamer1.0-pulseaudio`.

Use `gst-inspect-1.0 playbin souphttpsrc mpg123audiodec` to check the plugins if the desktop client cannot play a track.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/dev/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/).
