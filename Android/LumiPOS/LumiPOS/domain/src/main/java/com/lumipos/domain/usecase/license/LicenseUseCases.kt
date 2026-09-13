package com.lumipos.domain.usecase.license

import com.lumipos.data.license.DeviceIdentity
import com.lumipos.data.license.LicenseCheck
import com.lumipos.data.license.LicenseInfo
import com.lumipos.data.license.LicenseVerifier
import com.lumipos.data.repository.LicenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class LicenseStatus(
    val activated: Boolean = false,
    val plan: String? = null,
    val customer: String? = null,
    val expiresAt: String? = null,
    val expired: Boolean = false,
    val isTrial: Boolean = false
)

class GetLicenseStatusUseCase @Inject constructor(
    private val licenseRepository: LicenseRepository,
    private val deviceIdentity: DeviceIdentity
) {
    operator fun invoke(): Flow<LicenseStatus> = licenseRepository.licenseKey.map { key ->
        if (key.isNullOrBlank()) {
            LicenseStatus()
        } else {
            when (val check = LicenseVerifier.check(key, deviceIdentity.machineId())) {
                is LicenseCheck.Valid -> LicenseStatus(
                    activated = true,
                    plan = check.info.plan,
                    customer = check.info.customer,
                    expiresAt = check.info.expiresAt,
                    isTrial = check.info.plan.equals("trial", ignoreCase = true)
                )
                is LicenseCheck.Expired -> LicenseStatus(
                    plan = check.info.plan,
                    customer = check.info.customer,
                    expiresAt = check.info.expiresAt,
                    expired = true
                )
                else -> LicenseStatus()
            }
        }
    }
}

class ActivateLicenseUseCase @Inject constructor(
    private val licenseRepository: LicenseRepository,
    private val deviceIdentity: DeviceIdentity
) {
    data class Result(
        val success: Boolean,
        val message: String,
        val info: LicenseInfo? = null
    )

    suspend operator fun invoke(licenseKey: String): Result {
        val key = licenseKey.trim()
        if (key.isBlank()) return Result(false, "Enter a license key.")
        val machineId = deviceIdentity.machineId()
        return when (val check = LicenseVerifier.check(key, machineId)) {
            is LicenseCheck.Valid -> {
                licenseRepository.saveLicenseKey(key)
                Result(true, "License activated.", check.info)
            }
            is LicenseCheck.Expired -> Result(false, "This license expired on ${check.info.expiresAt}.")
            is LicenseCheck.WrongDevice -> Result(
                false,
                "This license belongs to machine ${check.info.machineId} and cannot be used on this device."
            )
            LicenseCheck.Invalid -> Result(false, "Invalid license key.")
        }
    }
}

class GetMachineIdUseCase @Inject constructor(
    private val deviceIdentity: DeviceIdentity
) {
    operator fun invoke(): String = deviceIdentity.machineId()
}