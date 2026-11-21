# PowerHub

Native Android companion project for Deye hybrid inverters.

## Current implementation (0.2.0)

- Native Kotlin + Jetpack Compose dashboard with clearly labelled demo telemetry
- Editable HTTP JSON endpoint, method, headers, body and dot-path field mappings
- Background HTTP monitoring with WorkManager, started from the dashboard
- Android notifications and optional Telegram alerts for low battery SOC and off-grid load at or above 3 kW
- Home-screen widget refreshed with the latest successful background readings
- Modbus TCP FC03 reader module and user-entered register settings
- Config persistence on device using DataStore
- GitHub Actions workflow to build a debug APK

Background checks run no more often than every 15 minutes. Android may defer them further to save battery. The first check runs immediately after enabling monitoring. The worker currently supports HTTP JSON only. Modbus settings are saved, but Modbus polling is not yet connected to the dashboard or background worker.

**This is an early version, not a certified or safety-critical alarm system.** Alerts depend on network access, Android background scheduling, notification permission, and correct API field mappings. The app does not assume a Deye register map. Confirm all readings against the inverter before relying on them.

## Build

The GitHub Actions workflow builds with Gradle 8.9 and Java 17 on every push to main, then uploads `PowerHub-debug-apk`. Open the Actions tab in this repository to download the artifact after a successful run.

## Hardware configuration

No Deye IP address, API endpoint, credentials or register map is hardcoded. Confirm the exact inverter model, firmware, logger/gateway and official protocol/register documentation before enabling live data. See [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Security

Do not commit API keys, passwords, logger credentials or Telegram bot tokens. App settings currently use local DataStore; secret values are not yet protected with Android Keystore-backed encryption. HTTP cleartext is enabled for local logger endpoints; use HTTPS wherever supported.
