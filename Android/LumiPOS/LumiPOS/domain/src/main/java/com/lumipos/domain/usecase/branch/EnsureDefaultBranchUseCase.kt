package com.lumipos.domain.usecase.branch

import com.lumipos.data.repository.StoreBranchesRepository
import com.lumipos.data.schema.StoreBranchEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class EnsureDefaultBranchUseCase @Inject constructor(
    private val storeBranchesRepository: StoreBranchesRepository
) {
    suspend operator fun invoke(defaultName: String = "Main Branch"): Long {
        val count = storeBranchesRepository.getBranchCount()
        if (count > 0) {
            return storeBranchesRepository.getAllActiveBranches().first().first().id
        }
        return storeBranchesRepository.insertBranch(
            StoreBranchEntity(name = defaultName, isActive = true)
        )
    }
}