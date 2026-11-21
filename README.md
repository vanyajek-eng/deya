# PowerHub

Android companion for a Deye inverter and Solarman Wi‑Fi logger.

## Current implementation (0.3.0)

- Local Solarman V5 TCP connection to the logger on port 8899
- Wi‑Fi subnet search for a logger that exposes the Solarman V5 port
- Continuous local polling, configurable from 3 to 60 seconds
- Foreground monitor notification and restart after device reboot (if enabled)
- Dashboard and home-screen widget updated from local readings
- Low-battery and off-grid load alerts, with optional Telegram delivery
- Deye Cloud credential form; fields are encrypted at rest, but Cloud API requests are not connected yet
- HTTP JSON mode retained as a separate source; background checks use WorkManager and may be delayed by Android
- Encrypted app configuration using Android Keystore; Android backup is disabled

The current register defaults target the single-phase **SUN-5K-SG03LP1-EU** context: grid voltage 150, grid power 169, load power 178, battery voltage 183 and SOC 184. These values need to be confirmed against the inverter firmware and the exact logger. Three-phase polling is deliberately blocked until its model-specific register map is confirmed.

Solarman V5 is proprietary and its public protocol descriptions are reverse-engineered. Check every displayed value against Deye Cloud or the inverter screen before relying on alerts. This app is not a safety-certified alarm system.

## Build

GitHub Actions builds a debug APK with Gradle 8.9 and Java 17 on pushes to main. Download `PowerHub-debug-apk` from the latest successful run in the Actions tab.

## Security

No credentials from screenshots or chat are included in the source. Cloud and Telegram settings are encrypted locally with an Android Keystore key and excluded from Android backup. The Deye Cloud form is not used for network requests yet.
