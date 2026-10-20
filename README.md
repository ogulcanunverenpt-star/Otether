# Otether

Otether is an experimental Android tethering app with a built-in HTTP proxy and a local traffic dashboard. It is designed to share a phone's mobile-data or Wi-Fi connection with nearby devices without requiring root access. Availability depends on the Android version, device hardware, carrier, and system tethering support.

## Features

- Choose mobile data or an existing Wi-Fi connection as the upstream source.
- Start a hotspot or Wi-Fi Direct group using Android networking APIs.
- Provide an HTTP proxy on port `8080`; connected devices must configure the proxy manually.
- Show connection status, connected clients, traffic totals, destination statistics, and recent proxy traffic in the dashboard.
- Show proxy connection metadata without recording request paths, headers, or payloads.
- Run the dashboard inside the Android app and serve it to connected devices on port `8081`.

## Technology

- Android application written in Kotlin.
- Jetpack Compose and Material 3 for the native interface.
- Android foreground service and Wi-Fi APIs for tethering.
- A local WebView dashboard built with HTML, CSS, and JavaScript.
- Gradle Kotlin DSL, Android Gradle Plugin `9.4.1`, Kotlin `2.2.10`, and Gradle wrapper `9.6.0`.

## Requirements

- Android Studio with a compatible JDK (JDK 17 or later).
- Android SDK Platform 37 and Android build tools installed.
- An Android device running Android 7.0 (API 24) or later for installation.
- Wi-Fi, mobile data, location, or nearby-device permissions as required by the selected sharing mode.

Some Android manufacturers and carriers restrict hotspot, Wi-Fi Direct, or tethering APIs. Therefore, a successful build does not guarantee that every mode works on every device.

## Build and test

From PowerShell in the project directory:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project directory in Android Studio and let it sync the Gradle project. Do not commit `local.properties`; it contains a machine-specific Android SDK path.

## Getting started

1. Install Otether on a supported Android device.
2. Enable the relevant connection (mobile data or Wi-Fi), Wi-Fi, and location services as needed.
3. Grant the Android permissions requested by the app.
4. Select the upstream connection and start tethering.
5. Connect another device to the phone's hotspot or Wi-Fi Direct group.
6. Configure that device's HTTP proxy to the address shown by Otether on port `8080`.
7. Open the dashboard at the address shown by Otether on port `8081`.

The app's dashboard is served over local HTTP and is not an authenticated public service. Treat it and the proxy as reachable by devices on the shared network. The source also contains a fallback hotspot passphrase; review the credential handling before distributing or relying on this project.

## Privacy and responsible use

Otether only observes traffic that a client explicitly routes through its HTTP proxy. For HTTPS, it records connection metadata such as the CONNECT destination and byte counts; TLS payloads remain encrypted and are neither decrypted nor stored. The dashboard does not provide a CA certificate. Use the app only on networks and devices you own or are explicitly authorized to administer.

## Project layout

```text
app/
  src/main/
    assets/web/       Dashboard frontend
    java/             Android UI, service, proxy, and Wi-Fi logic
    res/              Android resources and launcher icons
gradle/               Gradle wrapper and dependency catalog
```

## License

No `LICENSE` file is included. The terms for using, modifying, or redistributing this project have not been specified.
