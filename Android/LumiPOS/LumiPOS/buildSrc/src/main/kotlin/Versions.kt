object Versions {
    fun getVersionCode() = 1
    fun getVersionName() = "$majorVersion.$minorVersion.$patchVersion"
    private const val majorVersion = 1
    private const val minorVersion = 0
    private const val patchVersion = 0
}
