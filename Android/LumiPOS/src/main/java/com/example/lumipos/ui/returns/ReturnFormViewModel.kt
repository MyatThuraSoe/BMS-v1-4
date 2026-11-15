package com.example.lumipos.ui.returns

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.AuditLog
import com.example.lumipos.data.local.entity.Return
import com.example.lumipos.data.local.entity.ReturnItem
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.local.entity.SaleItem
import com.example.lumipos.data.local.entity.StockMovement
import com.example.lumipos.data.repository.AuditLogRepository
import com.example.lumipos.data.repository.ProductRepository
import com.example.lumipos.data.repository.ReturnRepository
import com.example.lumipos.data.repository.SaleRepository
import com.example.lumipos.data.repository.ShiftRepository
import com.example.lumipos.data.repository.StockMovementRepository
import com.example.lumipos.util.DateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One line on the return form: the original sale line plus the cashier's choice of how many
 * units to take back and whether they go back on the shelf.
 */
data class ReturnableLine(
    val saleItem: SaleItem,
    val returnable: Int,
    var returnQty: Int = 0,
    var restock: Boolean = true
) {
    val refundAmount: Double get() = saleItem.unitPrice * returnQty
}

class ReturnFormViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val productRepo = ProductRepository(db.productDao())
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
    private val stockMovementRepo = StockMovementRepository(db.stockMovementDao())
    private val auditLogRepo = AuditLogRepository(db.auditLogDao())
    private val shiftRepo = ShiftRepository(db.shiftDao())
    private val returnRepo = ReturnRepository(db.returnDao(), db.returnItemDao())
    private val loyaltyRepo = com.example.lumipos.data.repository.LoyaltyRepository(
        db.customerDao(), db.loyaltyTransactionDao(), db.settingDao()
    )

    private val _sale = MutableLiveData<Sale?>()
    val sale: LiveData<Sale?> = _sale

    private val _lines = MutableLiveData<List<ReturnableLine>>()
    val lines: LiveData<List<ReturnableLine>> = _lines

    private val _done = MutableLiveData(false)
    val done: LiveData<Boolean> = _done

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun load(saleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val sale = saleRepo.getById(saleId)
            val items = saleRepo.getItemsBySaleId(saleId)
            withContext(Dispatchers.Main) {
                _sale.value = sale
                _lines.value = items.map {
                    // qty - already refunded; a fully returned line offers nothing to take back.
                    ReturnableLine(it, (it.quantity - it.quantityRefunded).coerceAtLeast(0))
                }
            }
        }
    }

    /**
     * Commits the return: writes the header and line rows, restocks whatever the cashier marked
     * as sellable, stamps [SaleItem.quantityRefunded] so the units cannot be returned twice, adds
     * a stock movement per returned unit and bumps [Sale.amountRefunded].
     */
    fun processReturn(method: String, reason: String) {
        val lines = _lines.value ?: return
        val sale = _sale.value ?: return
        val selected = lines.filter { it.returnQty > 0 }
        if (selected.isEmpty()) {
            _error.value = "Select at least one item to return"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val totalRefund = selected.sumOf { it.refundAmount }
                val shift = shiftRepo.getCurrentShiftSync()

                val returnId = returnRepo.insert(
                    Return(
                        returnNumber = DateFormatter.generateReturnNumber(),
                        saleId = sale.id,
                        invoiceNumber = sale.invoiceNumber,
                        customerId = sale.customerId,
                        customerName = sale.customerName,
                        reason = reason,
                        refundMethod = method,
                        totalRefund = totalRefund,
                        shiftId = shift?.id
                    )
                )

                returnRepo.insertItems(
                    selected.map {
                        ReturnItem(
                            returnId = returnId,
                            saleItemId = it.saleItem.id,
                            productId = it.saleItem.productId,
                            productName = it.saleItem.productName,
                            sku = it.saleItem.sku,
                            quantity = it.returnQty,
                            unitPrice = it.saleItem.unitPrice,
                            totalPrice = it.refundAmount,
                            restocked = it.restock
                        )
                    }
                )

                for (line in selected) {
                    db.saleItemDao().addRefundedQuantity(line.saleItem.id, line.returnQty)
                    if (line.restock) {
                        productRepo.updateStock(line.saleItem.productId, line.returnQty)
                        stockMovementRepo.insert(
                            StockMovement(
                                productId = line.saleItem.productId,
                                productName = line.saleItem.productName,
                                sku = line.saleItem.sku,
                                movementType = "IN",
                                quantity = line.returnQty,
                                referenceType = "RETURN",
                                referenceId = returnId,
                                createdByName = "Cashier",
                                description = "Return against ${sale.invoiceNumber}"
                            )
                        )
                    }
                }

                db.saleDao().addRefund(sale.id, totalRefund)

                // Refunds that pay out as store credit top up the customer's wallet; every refund
                // also reverses the points the customer earned on the refunded amount.
                val customerId = sale.customerId
                if (customerId != null && customerId > 0) {
                    if (method == "STORE_CREDIT") {
                        loyaltyRepo.addStoreCredit(
                            customerId,
                            totalRefund,
                            "Refund on ${sale.invoiceNumber}"
                        )
                    }
                    loyaltyRepo.clawbackPoints(customerId, returnId, totalRefund)
                }

                auditLogRepo.insert(
                    AuditLog(
                        username = "Cashier",
                        action = "RETURN_CREATED",
                        entityType = "RETURN",
                        entityId = returnId,
                        description = "Return on ${sale.invoiceNumber}"
                    )
                )

                withContext(Dispatchers.Main) { _done.value = true }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _error.value = "Error processing return: ${e.message}" }
            }
        }
    }
}
