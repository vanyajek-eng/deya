# PowerHub

Native Android companion project for Deye hybrid inverters.

## Current implementation (0.1.0 scaffold)

- Native Kotlin + Jetpack Compose Android app
- Ukrainian dashboard with clearly labelled demo telemetry
- Editable HTTP JSON endpoint, method, headers, body and dot-path field mappings
- Modbus TCP FC03 client module with user-supplied register addresses
- Telegram Bot API sender module
- Config persistence on device using DataStore
- Home-screen widget scaffold
- Alert state-machine module for 30-second grid debounce, 3 kW off-grid overload and low-SOC thresholds
- GitHub Actions workflow to build a debug APK

**Important:** this is an initial development scaffold, not yet a finished live monitoring alarm system. The HTTP JSON source can be manually tested from the dashboard. The Modbus module, alert state machine and Telegram sender are implemented as modules but are not yet wired into continuous background polling and delivery. The widget currently displays static setup guidance. Do not rely on this version for power-safety alarms.

## Build

The GitHub Actions workflow builds with Gradle 8.9 and Java 17 on every push to main, then uploads PowerHub-debug-apk. Open the Actions tab in this repository to download the artifact after a successful run.

## Hardware configuration

No Deye IP address, API endpoint, credentials or register map is hardcoded. Confirm the exact inverter model, firmware, logger/gateway and official protocol/register documentation before enabling live data. See docs/CONFIGURATION.md.

## Security

Do not commit API keys, passwords, logger credentials or Telegram bot tokens. Secret storage needs Android Keystore-backed encryption before production use.
