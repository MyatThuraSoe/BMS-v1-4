package com.lumipos.domain.usecase.supplier

import com.lumipos.data.repository.SuppliersRepository
import com.lumipos.data.schema.SupplierEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSuppliersUseCase @Inject constructor(
    private val suppliersRepository: SuppliersRepository
) {
    fun getAllSuppliers(): Flow<List<SupplierEntity>> = suppliersRepository.getAllSuppliers()

    fun getAllActiveSuppliers(): Flow<List<SupplierEntity>> = suppliersRepository.getAllActiveSuppliers()

    suspend operator fun invoke(id: Long): SupplierEntity? = suppliersRepository.getSupplierById(id)
}

class AddSupplierUseCase @Inject constructor(
    private val suppliersRepository: SuppliersRepository
) {
    suspend operator fun invoke(
        name: String,
        contactPerson: String,
        email: String,
        phone: String,
        address: String,
        taxId: String,
        paymentTerms: String,
        notes: String
    ): Long {
        return suppliersRepository.insertSupplier(
            SupplierEntity(
                name = name,
                contactPerson = contactPerson,
                email = email,
                phone = phone,
                address = address,
                taxId = taxId,
                paymentTerms = paymentTerms,
                notes = notes,
                isActive = true
            )
        )
    }
}

class UpdateSupplierUseCase @Inject constructor(
    private val suppliersRepository: SuppliersRepository
) {
    suspend operator fun invoke(supplier: SupplierEntity) {
        suppliersRepository.updateSupplier(supplier)
    }

    suspend fun deleteSupplier(supplier: SupplierEntity) {
        suppliersRepository.deleteSupplier(supplier)
    }
}