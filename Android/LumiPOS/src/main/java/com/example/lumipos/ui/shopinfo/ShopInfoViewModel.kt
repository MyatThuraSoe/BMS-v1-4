package com.example.lumipos.ui.shopinfo

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.ShopInfo
import com.example.lumipos.data.repository.ShopInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShopInfoViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val shopInfoRepo = ShopInfoRepository(db.shopInfoDao(), db.receiptCustomizationDao(), db.settingDao())

    val shopInfo: LiveData<ShopInfo?> = shopInfoRepo.shopInfo

    private val _saveSuccess = MutableLiveData<Boolean>()
    val saveSuccess: LiveData<Boolean> = _saveSuccess

    fun updateShopInfo(shopInfo: ShopInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            shopInfoRepo.updateShopInfo(shopInfo)
            withContext(Dispatchers.Main) {
                _saveSuccess.value = true
            }
        }
    }
}
