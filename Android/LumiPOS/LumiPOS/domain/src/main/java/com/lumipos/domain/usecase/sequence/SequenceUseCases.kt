package com.lumipos.domain.usecase.sequence

import com.lumipos.data.repository.SequencesRepository
import javax.inject.Inject

class NextInvoiceNumberUseCase @Inject constructor(
    private val sequencesRepository: SequencesRepository
) {
    suspend operator fun invoke(): String = sequencesRepository.nextInvoiceNumber()
}

class NextOrderNumberUseCase @Inject constructor(
    private val sequencesRepository: SequencesRepository
) {
    suspend operator fun invoke(): String = sequencesRepository.nextOrderNumber()
}

class NextPurchaseNumberUseCase @Inject constructor(
    private val sequencesRepository: SequencesRepository
) {
    suspend operator fun invoke(): String = sequencesRepository.nextPurchaseNumber()
}