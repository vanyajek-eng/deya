package com.powerhub.deya

data class Telemetry(
    val soc: Float,
    val batteryVoltage: Float,
    val loadKw: Float,
    val gridPresent: Boolean,
    val source: String,
    val raw: String = ""
) {
    companion object {
        fun demo() = Telemetry(87f, 52.8f, 1.24f, true, "DEMO", """{"battery":{"soc":87,"voltage":52.8},"inverter":{"load_power":1.24},"grid":{"connected":true}}""")
    }
}
