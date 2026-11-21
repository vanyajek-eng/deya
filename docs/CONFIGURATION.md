# PowerHub configuration

## Current state
This is the initial Android project scaffold. The dashboard starts with clearly labelled demo telemetry; those values are not readings from your inverter.

## HTTP JSON
Set source to HTTP JSON, enter a complete URL, GET or POST, optional JSON headers/body, and dot-separated JSON object paths for SOC, battery voltage, load in kW, and grid state. Tap Refresh on the dashboard. Arrays and advanced JSONPath syntax are not yet supported.

## Modbus TCP
The repository includes an FC03 Modbus TCP client. Register addresses are intentionally user-supplied. The app does not assume a Deye register map, scale, signedness, or byte order. Confirm the exact inverter model, firmware, logger/gateway and official register map before using it. The current UI stores Modbus settings but does not yet bind the register reader into dashboard polling.

## Telegram and alerts
Bot token and chat ID fields are present, and the Telegram sender module is included. Background polling, notification delivery and wiring the alert state machine into the polling service are still under development. Do not treat this scaffold as a live alarm system.

## Security
Do not commit bot tokens, passwords, or private network credentials. The current configuration form is a development scaffold; secrets should be moved to encrypted storage backed by Android Keystore before production use. HTTP cleartext is enabled for local logger endpoints; use HTTPS wherever supported.
