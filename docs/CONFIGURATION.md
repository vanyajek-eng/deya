# PowerHub configuration

## Current state

The dashboard starts with clearly labelled demo telemetry; those values are not readings from your inverter.

## HTTP JSON

Set source to HTTP JSON, enter a complete URL, GET or POST, optional JSON headers/body, and dot-separated JSON object paths for SOC, battery voltage, load in kW, and grid state. Tap **Refresh** on the dashboard to read once. Arrays and advanced JSONPath syntax are not supported.

Select **Enable background monitoring** on the dashboard to save the current settings and start periodic checks. The first check runs immediately, then Android schedules checks at a minimum interval of 15 minutes and may defer them further. Select **Stop background monitoring** to cancel them. The worker requires HTTP JSON mode.

## Alerts and widget

The monitor displays Android notifications for a new low-SOC condition and for off-grid load at or above 3 kW. If Telegram bot token and chat ID are configured, it also sends the same alert to Telegram. Android notification permission is requested when the app starts on Android 13 and newer.

The home-screen widget shows the latest successful background reading. It remains empty until a background check has succeeded. Widget refreshes happen after those checks.

## Modbus TCP

The repository includes an FC03 Modbus TCP client. Register addresses are intentionally user-supplied. The UI stores Modbus settings, but Modbus is not yet connected to dashboard or background polling. The app does not assume a Deye register map, scale, signedness, or byte order. Confirm the exact inverter model, firmware, logger/gateway and official register map before using it.

## Security

Do not commit bot tokens, passwords, or private network credentials. The current configuration form stores values in local DataStore and secrets are not yet protected with Android Keystore-backed encryption. HTTP cleartext is enabled for local logger endpoints; use HTTPS wherever supported.