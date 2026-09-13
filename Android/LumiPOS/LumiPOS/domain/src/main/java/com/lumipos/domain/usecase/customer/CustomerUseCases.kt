package com.lumipos.domain.usecase.customer

import com.lumipos.data.repository.CustomersRepository
import com.lumipos.data.schema.CustomerEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetCustomersUseCase @Inject constructor(
    private val customersRepository: CustomersRepository
) {
    fun getAllActiveCustomers(): Flow<List<CustomerEntity>> = customersRepository.getAllActiveCustomers()

    fun getAllCustomers(): Flow<List<CustomerEntity>> = customersRepository.getAllCustomers()

    fun searchCustomers(query: String): Flow<List<CustomerEntity>> = customersRepository.searchCustomers(query)

    suspend operator fun invoke(id: Long): CustomerEntity? = customersRepository.getCustomerById(id)
}

class AddCustomerUseCase @Inject constructor(
    private val customersRepository: CustomersRepository
) {
    suspend operator fun invoke(
        name: String,
        phone: String,
        email: String,
        address: String
    ): Long {
        return customersRepository.insertCustomer(
            CustomerEntity(
                name = name,
                phone = phone,
                email = email,
                address = address,
                isActive = true
            )
        )
    }
}

class UpdateCustomerUseCase @Inject constructor(
    private val customersRepository: CustomersRepository
) {
    suspend operator fun invoke(customer: CustomerEntity) {
        customersRepository.updateCustomer(customer)
    }

    suspend fun deleteCustomer(customer: CustomerEntity) {
        customersRepository.deleteCustomer(customer)
    }
}