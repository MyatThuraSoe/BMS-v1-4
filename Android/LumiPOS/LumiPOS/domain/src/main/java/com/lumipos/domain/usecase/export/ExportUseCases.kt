package com.lumipos.domain.usecase.export

import com.lumipos.data.export.ExportImportManager
import javax.inject.Inject

class ExportBackupUseCase @Inject constructor(
    private val exportImportManager: ExportImportManager
) {
    suspend operator fun invoke(): Result<String> = exportImportManager.exportToJson()
}

class ImportBackupUseCase @Inject constructor(
    private val exportImportManager: ExportImportManager
) {
    suspend operator fun invoke(filePath: String, mode: ExportImportManager.ImportMode): Result<String> {
        return exportImportManager.importFromJson(filePath, mode)
    }
}

class ExportCsvUseCase @Inject constructor(
    private val exportImportManager: ExportImportManager
) {
    suspend operator fun invoke(fileName: String, content: String): Result<String> =
        exportImportManager.exportCsv(fileName, content)
}