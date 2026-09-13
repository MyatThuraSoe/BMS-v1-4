package com.lumipos.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumipos.data.repository.ShopInfoRepository
import com.lumipos.data.schema.ShopInfoEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class StoreInfoUiState(
    val shop: ShopInfoEntity? = null
)

@HiltViewModel
class StoreInfoViewModel @Inject constructor(
    private val shopInfoRepository: ShopInfoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StoreInfoUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            shopInfoRepository.getShopInfo().collectLatest { shop ->
                _uiState.value = StoreInfoUiState(shop = shop)
            }
        }
    }
}