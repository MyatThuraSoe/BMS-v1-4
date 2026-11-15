package com.example.lumipos.ui.ar

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.ArPayment
import com.example.lumipos.data.local.entity.Customer
import com.example.lumipos.data.local.entity.Sale
import com.example.lumipos.data.repository.ArPaymentRepository
import com.example.lumipos.data.repository.CustomerRepository
import com.example.lumipos.data.repository.SaleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class OutstandingInvoice(
    val sale: Sale,
    val totalPaid: Double,
    val balanceDue: Double
)

class AccountsReceivableViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val saleRepo = SaleRepository(db.saleDao(), db.saleItemDao())
    private val arPaymentRepo = ArPaymentRepository(db.arPaymentDao())
    private val customerRepo = CustomerRepository(db.customerDao())

    private val _outstandingInvoices = MutableLiveData<List<OutstandingInvoice>>()
    val outstandingInvoices: LiveData<List<OutstandingInvoice>> = _outstandingInvoices

    private val _selectedPayments = MutableLiveData<List<ArPayment>>()
    val selectedPayments: LiveData<List<ArPayment>> = _selectedPayments

    fun loadOutstandingInvoices() {
        viewModelScope.launch(Dispatchers.IO) {
            val allSales = saleRepo.getAllList()
            val outstanding = mutableListOf<OutstandingInvoice>()
            for (sale in allSales) {
                if (sale.paymentStatus != "PAID" && !sale.isVoided && sale.saleType == "CREDIT") {
                    // Sale.amountPaid is the authoritative "paid so far" figure: a credit checkout
                    // records its down payment there (POSViewModel.completeSale /
                    // OrdersViewModel.convertOrder) and no ArPayment row is written for it. Summing
                    // the ArPayment ledger instead silently dropped that down payment and overstated
                    // every balance by its amount.
                    val totalPaid = sale.amountPaid
                    val balanceDue = sale.totalAmount - totalPaid
                    if (balanceDue > 0) {
                        outstanding.add(OutstandingInvoice(sale, totalPaid, balanceDue))
                    }
                }
            }
            _outstandingInvoices.postValue(outstanding)
        }
    }

    fun recordPayment(saleId: Long, amount: Double, notes: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (amount <= 0.0) return@launch
            val sale = saleRepo.getById(saleId) ?: return@launch
            val payment = ArPayment(
                saleId = saleId,
                invoiceNumber = sale.invoiceNumber,
                customerId = sale.customerId ?: 0,
                customerName = sale.customerName,
                amount = amount,
                notes = notes
            )
            arPaymentRepo.insert(payment)

            // Add to what was already paid rather than recomputing from the ArPayment ledger. The
            // ledger only holds instalments taken *after* checkout, so recomputing from it wiped the
            // down payment out of Sale.amountPaid - the customer's credit was destroyed on the first
            // follow-up payment.
            val newTotalPaid = sale.amountPaid + amount
            val newStatus = if (newTotalPaid >= sale.totalAmount) "PAID" else "PARTIAL"
            saleRepo.update(sale.copy(
                amountPaid = newTotalPaid,
                paymentStatus = newStatus
            ))

            loadOutstandingInvoices()
        }
    }

    fun loadPayments(saleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _selectedPayments.postValue(arPaymentRepo.getBySaleIdList(saleId))
        }
    }
}
