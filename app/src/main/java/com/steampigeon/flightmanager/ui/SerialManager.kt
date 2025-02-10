package com.steampigeon.flightmanager.ui

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.steampigeon.flightmanager.data.RocketState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class SerialManager(private val port: UsbSerialPort) : SerialInputOutputManager.Listener {

    private val executor = Executors.newSingleThreadExecutor()

    private var bufferPosition = 0
    private val _locatorSerialReadBuffer = MutableStateFlow(ByteArray(16384))
    val locatorSerialReadBuffer: StateFlow<ByteArray> = _locatorSerialReadBuffer.asStateFlow()

    init {
        val serialIoManager = SerialInputOutputManager(port, this)
        executor.submit(serialIoManager)
    }

    override fun onNewData(data: ByteArray) {
        data.copyInto(_locatorSerialReadBuffer.value, bufferPosition)
    }

    override fun onRunError(e: Exception) {
        println("Error: ${e.message}")
    }
}
