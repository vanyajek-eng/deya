# Deye Pulse 2.0

Native Android monitor using the official Deye Cloud API. New dashboard, automatic telemetry discovery, and a ready-to-pin home screen widget.

## Main experience
- Overview: battery hero, household load, PV, grid and battery power, energy-system topology, daily counters, measurement age.
- Parameters: every field returned by device/latest plus supported-but-not-returned keys from device/measurePoints, original key, value and unit, categories, search, favorites and device metadata.
- Charts: up to 288 distinct locally collected snapshots, persistent across app restarts. Charts do not invent backfill and do not fetch historical readings from Cloud.
- Events: outage, SOC, off-grid load, stale-data and delivery errors.
- Settings: encrypted Cloud credentials, optional local JSON source, notification tests and widget metric selection.

Missing summary fields do not block the parameter catalogue. GridVoltageL1L2 is detected automatically. Grid power equal to zero is never treated as an outage. Unknown fields are retained.

## Widget
Four selectable API metrics, battery bar, grid state, live measurement age and refresh button.
Add with the overview button or Android launcher widget picker. Resizing switches between large and compact layouts.
During continuous monitoring it receives every completed API poll. Without it, WorkManager refreshes periodically (15 minutes or later, as allowed by Android); tapping refresh requests a background update without opening the app.
The widget never uploads or modifies inverter settings.

## Installation
Download Deye-Pulse-APK from the latest successful Actions run, unzip and install Deye-Pulse.apk over the previous app.
Package and encrypted settings key remain unchanged. CI restores the existing personal debug signing key.
Cloud refresh frequency is governed by the logger, not phone polling. No PC is required. Allow notifications and unrestricted background battery use.

## Alerts and data quality
New alarms require online, sufficiently recent measurements. Cached snapshots are displayed with their original timestamp.
The grid confirmation interval uses distinct measurement timestamps; this can take multiple logger uploads.
Defaults: SOC 20%, off-grid load 3000 W, grid confirmation 30 seconds, maximum alarm sample age 900 seconds.
All thresholds are editable. Offline loggers and communication failures produce connection alarms rather than inferred power outages.
The app is read-only and does not write inverter configuration.

## Validation
CI runs JVM tests, Android lint, APK compilation, and instrumented UI/widget inflation checks on an API 35 Pixel emulator.
Synthetic telemetry exists only in androidTest; it is not shipped as live or demo data in the app.
Device-validation screenshots are internal test output, not evidence of connection to a physical inverter.
Production hardware validation is still required.

## Official protocol references
- https://github.com/DeyeCloudDevelopers/deye-openapi-client-sample-code
- https://developer.deyecloud.com/openmcp/docs/deye-open-mcp-tools.html

This is a debug-signed personal-use APK with a specialUse foreground service for continuous household power alarms.
