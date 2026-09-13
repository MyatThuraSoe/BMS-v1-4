package com.lumipos.domain.usecase.print

import com.lumipos.data.print.BluetoothPrinterManager
import com.lumipos.data.print.PrinterDevice
import com.lumipos.data.print.ReceiptData
import javax.inject.Inject

class GetPairedPrintersUseCase @Inject constructor(
    private val manager: BluetoothPrinterManager
) {
    operator fun invoke(): Result<List<PrinterDevice>> = manager.getPairedPrinters()
}

class PrintReceiptUseCase @Inject constructor(
    private val manager: BluetoothPrinterManager
) {
    suspend operator fun invoke(address: String, payload: ReceiptData): Result<String> =
        manager.printReceipt(address, payload)
}