# Deye Pulse

New Android app built from scratch, separate from the old PowerHub app.
Package: `com.powerhub.pulse`. Android 8+.

## What works in the source
- Official Deye Cloud token authentication, SHA-256 password, encrypted on-device credentials.
- Latest inverter data by SN, strict field and unit parsing, timestamp and offline checks.
- Dashboard with real readings, sample-age labels, load history collected during the session.
- User-started continuous monitoring with visible stop action and wake lock.
- Grid loss/restoration confirmed across measurements spanning 30 seconds.
- Low SOC at 20%, off-grid load at 3000 W by default; editable thresholds and hysteresis.
- Persistent alarm latches and a 100-event journal.
- Local Android alerts and optional Telegram; separate delivery tests.
- Home screen widget with measurement timestamp.
- Optional local HTTP JSON endpoint.

## Install
Open Actions → latest successful **Deye Pulse APK** run → artifact **Deye-Pulse-APK**.
Unzip and install Deye-Pulse.apk. This is a debug-signed personal-use build.
The app installs separately from PowerHub. Stop the old app's monitor to avoid duplicate alerts.
Enter your Deye API credentials yourself; none are embedded in this repository.
Choose the correct region, enter inverter SN, press **Зберегти й перевірити API**.
Allow notifications, run both delivery tests as needed, allow unrestricted battery use, then start monitoring.

## Data and latency
Cloud API is polled every 15 seconds by default; this does not make the logger upload faster.
A cached snapshot cannot confirm a grid transition. Confirmation uses distinct measurement timestamps.
Cloud alarms include logger upload latency and cannot guarantee 30-second real-world delivery.
No PC or server is needed, but the phone must have internet, the monitor must be running,
and the inverter logger/router need connectivity during an outage.
An offline logger or old snapshot produces a connection warning, never an inferred grid-loss alarm.
The default maximum sample age is 10 minutes, adjustable.
Android or device power management may stop background work; force-stopping the app prevents alarms.

## API measure points
Deye products may use different keys. Defaults: SOC, BatteryVoltage, TotalConsumptionPower, GridVoltage.
Missing keys are reported with available keys; change mappings in Settings.
Grid voltage >=80 V means present. Alternatively use an explicit boolean/0-or-1 grid field.
Never infer outages from grid power being zero.
Units are read from the cloud unit field, with a user-selected W/kW fallback.
EU and US endpoints follow Deye's official sample. India is configurable and must be verified against your developer region.

Local JSON GET schema:
```json
{"soc":70,"batteryVoltage":52.2,"loadWatts":1100,"gridPresent":true,"timestamp":1700000000}
```
Timestamp must be current Unix seconds from the actual measurement.

## Validation
CI runs JVM tests, Android lint, and APK compilation. Hardware and phone behavior need real-device testing.

## References
- https://github.com/DeyeCloudDevelopers/deye-openapi-client-sample-code
- https://developer.deyecloud.com/openmcp/docs/deye-open-mcp-tools.html
- https://developer.android.com/develop/background-work/services/fgs/service-types

This personal-use build declares a specialUse foreground service for ongoing household power alarms.
A public Google Play release would require review of that use case and production signing.
