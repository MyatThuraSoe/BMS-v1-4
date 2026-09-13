package com.lumipos.app.ui.receipts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.print.ReceiptBuilder
import com.lumipos.data.print.ReceiptData
import com.lumipos.data.repository.ShopInfoRepository
import com.lumipos.data.schema.ReceiptCustomizationEntity
import com.lumipos.data.schema.ShopInfoEntity
import com.lumipos.domain.usecase.receipt.GetReceiptCustomizationUseCase
import com.lumipos.domain.usecase.receipt.SaveReceiptCustomizationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReceiptCustomizationUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val headerText: String = "Thank you for your purchase!",
    val mainMessage: String = "",
    val footerText: String = "Come again!",
    val paperSize: Int = 80,
    val timeFormat: String = "12h",
    val logoSize: Int = 50,
    val showLogo: Boolean = false,
    val showShopName: Boolean = true,
    val showAddress: Boolean = true,
    val showPhone: Boolean = true,
    val headerAlign: String = "CENTER",
    val fontSize: Int = 12,
    val dividerStyle: String = "DASHED",
    val boldShopName: Boolean = true,
    val showQRCode: Boolean = false,
    val showCreditInfo: Boolean = true,
    val showDiscountInfo: Boolean = true,
    val showTaxInfo: Boolean = true,
    val showItemSku: Boolean = false,
    val shopName: String = "",
    val shopAddress: String = "",
    val shopPhone: String = "",
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class ReceiptCustomizationViewModel @Inject constructor(
    private val getReceiptCustomizationUseCase: GetReceiptCustomizationUseCase,
    private val saveReceiptCustomizationUseCase: SaveReceiptCustomizationUseCase,
    private val shopInfoRepository: ShopInfoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReceiptCustomizationUiState())
    val uiState: StateFlow<ReceiptCustomizationUiState> = _uiState

    init {
        viewModelScope.launch {
            shopInfoRepository.getShopInfo().collect { shop ->
                _uiState.update {
                    it.copy(
                        shopName = shop?.shopName.orEmpty(),
                        shopAddress = shop?.address.orEmpty(),
                        shopPhone = shop?.phone.orEmpty()
                    )
                }
            }
        }
        viewModelScope.launch {
            getReceiptCustomizationUseCase().collect { c ->
                if (c != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            headerText = c.headerText,
                            mainMessage = c.mainMessage,
                            footerText = c.footerText,
                            paperSize = c.paperSize,
                            timeFormat = c.timeFormat,
                            logoSize = c.logoSize,
                            showLogo = c.showLogo,
                            showShopName = c.showShopName,
                            showAddress = c.showAddress,
                            showPhone = c.showPhone,
                            headerAlign = c.headerAlign,
                            fontSize = c.fontSize,
                            dividerStyle = c.dividerStyle,
                            boldShopName = c.boldShopName,
                            showQRCode = c.showQRCode,
                            showCreditInfo = c.showCreditInfo,
                            showDiscountInfo = c.showDiscountInfo,
                            showTaxInfo = c.showTaxInfo,
                            showItemSku = c.showItemSku
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun buildPreviewReceipt(): ReceiptData {
        val s = _uiState.value
        return ReceiptBuilder.build(
            customization = ReceiptCustomizationEntity(
                headerText = s.headerText,
                mainMessage = s.mainMessage,
                footerText = s.footerText,
                paperSize = s.paperSize,
                timeFormat = s.timeFormat,
                logoSize = s.logoSize,
                showLogo = s.showLogo,
                showShopName = s.showShopName,
                showAddress = s.showAddress,
                showPhone = s.showPhone,
                headerAlign = s.headerAlign,
                fontSize = s.fontSize,
                dividerStyle = s.dividerStyle,
                boldShopName = s.boldShopName,
                showQRCode = s.showQRCode,
                showCreditInfo = s.showCreditInfo,
                showDiscountInfo = s.showDiscountInfo,
                showTaxInfo = s.showTaxInfo,
                showItemSku = s.showItemSku
            ),
            shop = ShopInfoEntity(
                shopName = s.shopName,
                address = s.shopAddress,
                phone = s.shopPhone
            ),
            invoice = "INV-1001",
            dateTime = System.currentTimeMillis(),
            metaLines = listOf("Cashier" to "1", "Method" to "CASH", "Customer" to "Walk-in"),
            items = listOf(
                ReceiptBuilder.LineItem(name = "Coffee Latte", quantity = 2, unitPrice = 5.00, barcode = "8972103045"),
                ReceiptBuilder.LineItem(name = "Green Tea", quantity = 1, unitPrice = 3.50, barcode = ""),
                ReceiptBuilder.LineItem(name = "Cheese Cake", quantity = 1, unitPrice = 4.50, barcode = "7829401123")
            ),
            amountPaid = 20.00
        )
    }

    fun setHeaderText(v: String) = _uiState.update { it.copy(headerText = v) }
    fun setMainMessage(v: String) = _uiState.update { it.copy(mainMessage = v) }
    fun setFooterText(v: String) = _uiState.update { it.copy(footerText = v) }
    fun setPaperSize(v: Int) = _uiState.update { it.copy(paperSize = v) }
    fun setTimeFormat(v: String) = _uiState.update { it.copy(timeFormat = v) }
    fun setLogoSize(v: Int) = _uiState.update { it.copy(logoSize = v) }
    fun setShowLogo(v: Boolean) = _uiState.update { it.copy(showLogo = v) }
    fun setShowShopName(v: Boolean) = _uiState.update { it.copy(showShopName = v) }
    fun setShowAddress(v: Boolean) = _uiState.update { it.copy(showAddress = v) }
    fun setShowPhone(v: Boolean) = _uiState.update { it.copy(showPhone = v) }
    fun setHeaderAlign(v: String) = _uiState.update { it.copy(headerAlign = v) }
    fun setFontSize(v: Int) = _uiState.update { it.copy(fontSize = v) }
    fun setDividerStyle(v: String) = _uiState.update { it.copy(dividerStyle = v) }
    fun setBoldShopName(v: Boolean) = _uiState.update { it.copy(boldShopName = v) }
    fun setShowQRCode(v: Boolean) = _uiState.update { it.copy(showQRCode = v) }
    fun setShowCreditInfo(v: Boolean) = _uiState.update { it.copy(showCreditInfo = v) }
    fun setShowDiscountInfo(v: Boolean) = _uiState.update { it.copy(showDiscountInfo = v) }
    fun setShowTaxInfo(v: Boolean) = _uiState.update { it.copy(showTaxInfo = v) }
    fun setShowItemSku(v: Boolean) = _uiState.update { it.copy(showItemSku = v) }

    fun save() {
        val s = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                saveReceiptCustomizationUseCase(
                    headerText = s.headerText,
                    mainMessage = s.mainMessage,
                    footerText = s.footerText,
                    paperSize = s.paperSize,
                    timeFormat = s.timeFormat,
                    logoSize = s.logoSize,
                    showLogo = s.showLogo,
                    showShopName = s.showShopName,
                    showAddress = s.showAddress,
                    showPhone = s.showPhone,
                    headerAlign = s.headerAlign,
                    fontSize = s.fontSize,
                    dividerStyle = s.dividerStyle,
                    boldShopName = s.boldShopName,
                    showQRCode = s.showQRCode,
                    showCreditInfo = s.showCreditInfo,
                    showDiscountInfo = s.showDiscountInfo,
                    showTaxInfo = s.showTaxInfo,
                    showItemSku = s.showItemSku
                )
                _uiState.update { it.copy(isSaving = false, message = "Receipt settings saved") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }
}