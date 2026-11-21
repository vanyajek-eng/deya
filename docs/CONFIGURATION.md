# PowerHub configuration

## Local Solarman V5

Use the inverter and logger on the same local network. The app can scan the active IPv4 /24 network for a device accepting TCP connections on port 8899. It then reads the logger serial number from the label; the logger serial and inverter serial are different fields.

The current default register map is for the single-phase Deye SUN-5K-SG03LP1-EU context:

- Grid voltage: 150, scale 0.1 V
- Grid power: 169
- Load power: 178, watts
- Battery voltage: 183, scale 0.01 V
- Battery SOC: 184, percent

Those addresses are defaults, not guaranteed for every firmware or inverter model. The inputs remain editable. Three-phase mode is shown in settings, but polling is blocked until its model-specific register map is confirmed.

The local reader wraps Modbus RTU FC03 requests in Solarman V5 frames. Public protocol material is reverse-engineered and logger firmware compatibility can vary. Verify readings against the inverter before enabling alarms.

## Polling and alerts

Local monitoring runs as an Android foreground service at the configured interval (3–60 seconds) and displays an ongoing notification. Enable **Автозапуск після перезавантаження** to have the app attempt to resume after reboot. Android power-management settings can still restrict background activity.

The monitor sends Android notifications for a new low-SOC condition and off-grid load at or above the configured watt limit. If Telegram details are configured, it also attempts Telegram delivery.

HTTP JSON remains available as an alternate source. Its WorkManager background schedule runs at a minimum of 15 minutes and may be delayed further by Android.

## Deye Cloud

The settings screen contains fields for the Deye Cloud application credentials and account. They are encrypted on the phone, but the Deye Cloud API is not connected yet. Do not expect remote readings from those fields in this build.

## Security

All saved app settings are encrypted with a key held in Android Keystore. App data backup is disabled because encrypted settings cannot be restored without the device key. No credentials shown in screenshots or conversation are embedded in the repository.
