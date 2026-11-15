package com.example.lumipos.ui.settings

import android.app.Application
import androidx.lifecycle.*
import com.example.lumipos.LumiPOSApplication
import com.example.lumipos.data.local.entity.ReceiptCustomization
import com.example.lumipos.data.local.entity.ShopInfo
import com.example.lumipos.data.repository.ShopInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as LumiPOSApplication).database
    private val shopInfoRepo = ShopInfoRepository(db.shopInfoDao(), db.receiptCustomizationDao(), db.settingDao())

    val shopInfo: LiveData<ShopInfo?> = shopInfoRepo.shopInfo
    val receiptCustomization: LiveData<ReceiptCustomization?> = shopInfoRepo.receiptCustomization

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

    fun updateReceiptCustomization(customization: ReceiptCustomization) {
        viewModelScope.launch(Dispatchers.IO) {
            shopInfoRepo.updateReceiptCustomization(customization)
            withContext(Dispatchers.Main) {
                _saveSuccess.value = true
            }
        }
    }

    private val loyaltyRepo =
        com.example.lumipos.data.repository.LoyaltyRepository(db.customerDao(), db.loyaltyTransactionDao(), db.settingDao())

    private val _loyaltySettings = MutableLiveData<LoyaltySettings?>()
    val loyaltySettings: LiveData<LoyaltySettings?> = _loyaltySettings

    private val _loyaltySaved = MutableLiveData<Boolean>()
    val loyaltySaved: LiveData<Boolean> = _loyaltySaved

    data class LoyaltySettings(val enabled: Boolean, val pointsPerUnit: Double, val redeemValue: Double)

    fun loadLoyaltySettings() {
        viewModelScope.launch(Dispatchers.IO) {
            _loyaltySettings.postValue(
                LoyaltySettings(
                    enabled = loyaltyRepo.isEnabled(),
                    pointsPerUnit = loyaltyRepo.pointsPerUnit(),
                    redeemValue = loyaltyRepo.redeemValue()
                )
            )
        }
    }

    fun saveLoyaltySettings(enabled: Boolean, pointsPerUnit: Double, redeemValue: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            loyaltyRepo.setEnabled(enabled)
            loyaltyRepo.setPointsPerUnit(pointsPerUnit)
            loyaltyRepo.setRedeemValue(redeemValue)
            withContext(Dispatchers.Main) {
                _loyaltySaved.value = true
            }
        }
    }

    private val _screenshotBlocked = MutableLiveData<Boolean>()
    val screenshotBlocked: LiveData<Boolean> = _screenshotBlocked

    fun loadSecuritySettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val blocked = db.settingDao().getValue(SECURITY_SCREENSHOT_KEY)?.toBoolean() ?: true
            _screenshotBlocked.postValue(blocked)
        }
    }

    fun setScreenshotBlocked(blocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            db.settingDao().insert(
                com.example.lumipos.data.local.entity.Setting(SECURITY_SCREENSHOT_KEY, blocked.toString())
            )
            _screenshotBlocked.postValue(blocked)
        }
    }

    private val _activeRole = MutableLiveData<com.example.lumipos.util.Permissions.Role>()
    val activeRole: LiveData<com.example.lumipos.util.Permissions.Role> = _activeRole

    private val _autoBackupEnabled = MutableLiveData<Boolean>()
    val autoBackupEnabled: LiveData<Boolean> = _autoBackupEnabled

    // ------------------------------------------------------------------ LAN sync

    data class SyncConfigUi(val mode: String, val masterIp: String, val port: String, val token: String)

    private val _syncConfig = MutableLiveData<SyncConfigUi>()
    val syncConfig: LiveData<SyncConfigUi> = _syncConfig

    fun loadSyncConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = db.settingDao()
            _syncConfig.postValue(
                SyncConfigUi(
                    mode = dao.getValue(com.example.lumipos.sync.SyncSettings.KEY_MODE)
                        ?: com.example.lumipos.sync.SyncSettings.MODE_OFF,
                    masterIp = dao.getValue(com.example.lumipos.sync.SyncSettings.KEY_MASTER_IP) ?: "",
                    port = dao.getValue(com.example.lumipos.sync.SyncSettings.KEY_PORT)
                        ?: com.example.lumipos.sync.SyncSettings.DEFAULT_PORT,
                    token = dao.getValue(com.example.lumipos.sync.SyncSettings.KEY_TOKEN)
                        ?: com.example.lumipos.sync.SyncSettings.DEFAULT_TOKEN
                )
            )
        }
    }

    fun saveSyncConfig(mode: String, masterIp: String, port: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = db.settingDao()
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.SyncSettings.KEY_MODE, mode))
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.SyncSettings.KEY_MASTER_IP, masterIp))
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.SyncSettings.KEY_PORT, port))
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.SyncSettings.KEY_TOKEN, token))
            _syncConfig.postValue(SyncConfigUi(mode = mode, masterIp = masterIp, port = port, token = token))
        }
    }

    // ---------------------------------------------------------------- cloud backup

    data class CloudConfigUi(
        val enabled: Boolean,
        val url: String,
        val username: String,
        val password: String,
        val status: String
    )

    private val _cloudConfig = MutableLiveData<CloudConfigUi>()
    val cloudConfig: LiveData<CloudConfigUi> = _cloudConfig

    fun loadCloudConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = db.settingDao()
            _cloudConfig.postValue(
                CloudConfigUi(
                    enabled = dao.getValue(com.example.lumipos.sync.CloudBackup.KEY_ENABLED)?.toBoolean() ?: false,
                    url = dao.getValue(com.example.lumipos.sync.CloudBackup.KEY_URL) ?: "",
                    username = dao.getValue(com.example.lumipos.sync.CloudBackup.KEY_USERNAME) ?: "",
                    password = dao.getValue(com.example.lumipos.sync.CloudBackup.KEY_PASSWORD) ?: "",
                    status = dao.getValue(com.example.lumipos.sync.CloudBackup.KEY_STATUS) ?: ""
                )
            )
        }
    }

    fun saveCloudConfig(enabled: Boolean, url: String, username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = db.settingDao()
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.CloudBackup.KEY_ENABLED, enabled.toString()))
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.CloudBackup.KEY_URL, url))
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.CloudBackup.KEY_USERNAME, username))
            dao.insert(com.example.lumipos.data.local.entity.Setting(com.example.lumipos.sync.CloudBackup.KEY_PASSWORD, password))
            _cloudConfig.postValue(
                CloudConfigUi(
                    enabled = enabled,
                    url = url,
                    username = username,
                    password = password,
                    status = dao.getValue(com.example.lumipos.sync.CloudBackup.KEY_STATUS) ?: ""
                )
            )
        }
    }

    fun loadAutoBackupEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            val enabled = db.settingDao()
                .getValue(com.example.lumipos.worker.AutoBackupScheduler.KEY_AUTO_BACKUP_ENABLED)
                ?.toBoolean() ?: false
            _autoBackupEnabled.postValue(enabled)
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            db.settingDao().insert(
                com.example.lumipos.data.local.entity.Setting(
                    com.example.lumipos.worker.AutoBackupScheduler.KEY_AUTO_BACKUP_ENABLED,
                    enabled.toString()
                )
            )
            val context = getApplication<Application>()
            if (enabled) {
                com.example.lumipos.worker.AutoBackupScheduler.schedule(context)
            } else {
                com.example.lumipos.worker.AutoBackupScheduler.cancel(context)
            }
            _autoBackupEnabled.postValue(enabled)
        }
    }

    fun loadActiveRole() {
        viewModelScope.launch(Dispatchers.IO) {
            val role = com.example.lumipos.util.Permissions.roleFrom(
                db.settingDao().getValue(com.example.lumipos.util.Permissions.KEY_ACTIVE_ROLE)
            )
            _activeRole.postValue(role)
        }
    }

    fun setActiveRole(role: com.example.lumipos.util.Permissions.Role) {
        viewModelScope.launch(Dispatchers.IO) {
            db.settingDao().insert(
                com.example.lumipos.data.local.entity.Setting(
                    com.example.lumipos.util.Permissions.KEY_ACTIVE_ROLE,
                    role.name
                )
            )
            _activeRole.postValue(role)
        }
    }

    companion object {
        /** Setting key shared with MainActivity, which applies the window flag. */
        const val SECURITY_SCREENSHOT_KEY = "security_screenshot_blocked"
    }
}
