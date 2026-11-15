package com.example.lumipos.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothSpp : Closeable {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile
    private var listening = false
    private var readThread: Thread? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val created = device.createRfcommSocketToServiceRecord(SPP_UUID)
        created.connect()
        socket = created
        input = created.inputStream
        output = created.outputStream
    }

    @SuppressLint("MissingPermission")
    fun connectMac(address: String): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        connect(adapter.getRemoteDevice(address))
        return isConnected
    }

    fun write(bytes: ByteArray): Boolean {
        val stream = output ?: return false
        return try {
            stream.write(bytes)
            stream.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startReading(onLine: (String) -> Unit) {
        val stream = input ?: return
        listening = true
        readThread = Thread {
            val buffer = ByteArray(1024)
            val builder = StringBuilder()
            while (listening) {
                val read = try {
                    stream.read(buffer)
                } catch (e: Exception) {
                    -1
                }
                if (read <= 0) break
                for (index in 0 until read) {
                    val character = buffer[index].toInt().toChar()
                    if (character == '\n' || character == '\r') {
                        if (builder.isNotEmpty()) {
                            val line = builder.toString().trim()
                            builder.clear()
                            if (line.isNotEmpty()) onLine(line)
                        }
                    } else {
                        builder.append(character)
                    }
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun close() {
        listening = false
        readThread?.interrupt()
        readThread = null
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
