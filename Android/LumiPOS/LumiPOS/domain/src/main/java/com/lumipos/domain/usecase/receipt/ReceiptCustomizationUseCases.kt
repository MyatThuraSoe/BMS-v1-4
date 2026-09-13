package com.lumipos.domain.usecase.receipt

import com.lumipos.data.repository.ReceiptCustomizationsRepository
import com.lumipos.data.schema.ReceiptCustomizationEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetReceiptCustomizationUseCase @Inject constructor(
    private val receiptCustomizationsRepository: ReceiptCustomizationsRepository
) {
    operator fun invoke(): Flow<ReceiptCustomizationEntity?> = receiptCustomizationsRepository.getReceiptCustomization()

    suspend fun getOnce(): ReceiptCustomizationEntity? = receiptCustomizationsRepository.getReceiptCustomizationOnce()
}

class SaveReceiptCustomizationUseCase @Inject constructor(
    private val receiptCustomizationsRepository: ReceiptCustomizationsRepository
) {
    suspend operator fun invoke(
        headerText: String,
        mainMessage: String,
        footerText: String,
        paperSize: Int,
        timeFormat: String,
        logoSize: Int,
        showLogo: Boolean,
        showShopName: Boolean,
        showAddress: Boolean,
        showPhone: Boolean,
        headerAlign: String,
        fontSize: Int,
        dividerStyle: String,
        boldShopName: Boolean,
        showQRCode: Boolean,
        showCreditInfo: Boolean,
        showDiscountInfo: Boolean,
        showTaxInfo: Boolean,
        showItemSku: Boolean
    ) {
        receiptCustomizationsRepository.save(
            ReceiptCustomizationEntity(
                headerText = headerText,
                mainMessage = mainMessage,
                footerText = footerText,
                paperSize = paperSize,
                timeFormat = timeFormat,
                logoSize = logoSize,
                showLogo = showLogo,
                showShopName = showShopName,
                showAddress = showAddress,
                showPhone = showPhone,
                headerAlign = headerAlign,
                fontSize = fontSize,
                dividerStyle = dividerStyle,
                boldShopName = boldShopName,
                showQRCode = showQRCode,
                showCreditInfo = showCreditInfo,
                showDiscountInfo = showDiscountInfo,
                showTaxInfo = showTaxInfo,
                showItemSku = showItemSku
            )
        )
    }
}