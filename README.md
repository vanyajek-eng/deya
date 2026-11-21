# PowerHub

Native Android companion for Deye hybrid inverters.

## Status

Initial project bootstrap. The app includes a configurable data-source architecture and demo mode. Live inverter communication must be configured for the exact logger/protocol in **Settings → Connection**; no unverified Deye register map or endpoint is assumed.

## Build

Open this repository in Android Studio or run `./gradlew assembleDebug`. GitHub Actions builds a debug APK on every push to `main` and publishes it as an artifact.

## Features

- Ukrainian dashboard and live/demo telemetry
- Editable HTTP/JSON and Modbus TCP connection settings
- JSONPath-like field mappings and Modbus register definitions
- Import/export configuration
- Connection diagnostics and raw response preview
- Grid transition debounce (30 seconds), overload warning (3 kW off-grid), low battery warning (20%)
- Telegram notifications
- Android home-screen widget

See `docs/CONFIGURATION.md` for connection setup and security notes.