package com.lumipos.app.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.app.BuildConfig
import com.lumipos.data.repository.ShopInfoRepository
import com.lumipos.domain.usecase.license.GetLicenseStatusUseCase
import com.lumipos.domain.usecase.license.GetMachineIdUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AboutUiState(
    val shopName: String = "",
    val address: String = "",
    val phone: String = "",
    val currency: String = "$",
    val activated: Boolean = false,
    val plan: String? = null,
    val customer: String? = null,
    val expiresAt: String? = null,
    val expired: Boolean = false,
    val machineId: String = "",
    val version: String = BuildConfig.VERSION_NAME
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val getLicenseStatus: GetLicenseStatusUseCase,
    private val getMachineId: GetMachineIdUseCase,
    private val shopInfoRepository: ShopInfoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val machineId = getMachineId()
            getLicenseStatus().collect { lic ->
                val shop = shopInfoRepository.getShopInfoOnce()
                _uiState.value = AboutUiState(
                    shopName = shop?.shopName ?: "",
                    address = shop?.address ?: "",
                    phone = shop?.phone ?: "",
                    currency = shop?.currency ?: "$",
                    activated = lic.activated,
                    plan = lic.plan,
                    customer = lic.customer,
                    expiresAt = lic.expiresAt,
                    expired = lic.expired,
                    machineId = machineId
                )
            }
        }
    }
}