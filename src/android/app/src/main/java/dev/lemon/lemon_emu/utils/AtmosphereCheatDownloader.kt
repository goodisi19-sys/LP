package dev.lemon.lemon_emu.utils
​import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
​data class DownloadCheatsResult(
val success: Boolean,
val totalCheatsCount: Int = 0,
val graphicsCheatsCount: Int = 0,
val errorMessage: String? = null
)
​object AtmosphereCheatDownloader {
suspend fun downloadAndInstallCheats(
titleIdHex: String,
buildIdHex: String,
baseDirectory: String
): DownloadCheatsResult = withContext(Dispatchers.IO) {
val formattedTitleId = titleIdHex.uppercase().padStart(16, '0')
val targetBuildId = buildIdHex.uppercase()
val urlString = "https://raw.githubusercontent.com/HamletDuFromage/switch-cheats-db/master/titles/$formattedTitleId.json"
​try {
val url = URL(urlString)
val connection = (url.openConnection() as HttpURLConnection).apply {
connectTimeout = 10000
readTimeout = 15000
requestMethod = "GET"
}
​if (connection.responseCode != HttpURLConnection.HTTP_OK) {
return@withContext DownloadCheatsResult(
success = false,
errorMessage = "Читы не найдены (Код ${connection.responseCode})"
)
}
​val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
val rootJson = JSONObject(jsonText)
​if (!rootJson.has(targetBuildId)) {
return@withContext DownloadCheatsResult(
success = false,
errorMessage = "Для версии $targetBuildId читов нет в базе"
)
}
​val cheatsJson = rootJson.getJSONObject(targetBuildId)
val standardCheats = StringBuilder()
val graphicsCheats = StringBuilder()
​var totalCount = 0
var gfxCount = 0
​val keys = cheatsJson.keys()
while (keys.hasNext()) {
val cheatTitle = keys.next()
val cheatBody = cheatsJson.getString(cheatTitle)
val block = "{cheatTitle}\ncheatBody\n\n"
​totalCount++
if (AtmosphereCheatHelper.isGraphicsTitle(cheatTitle)) {
graphicsCheats.append(block)
gfxCount++
} else {
standardCheats.append(block)
}
}
​val mainCheatsDir = File(baseDirectory, "load/mods/$formattedTitleId/cheats")
mainCheatsDir.mkdirs()
File(mainCheatsDir, "$targetBuildId.txt").writeText(standardCheats.toString())
​if (graphicsCheats.isNotEmpty()) {
val gfxCheatsDir = File(baseDirectory, "load/mods/$formattedTitleId/60FPS_Graphics_Patches/cheats")
gfxCheatsDir.mkdirs()
File(gfxCheatsDir, "$targetBuildId.txt").writeText(graphicsCheats.toString())
}
​return@withContext DownloadCheatsResult(
success = true,
totalCheatsCount = totalCount,
graphicsCheatsCount = gfxCount
)
} catch (e: Exception) {
return@withContext DownloadCheatsResult(
success = false,
errorMessage = "Сетевая ошибка: ${e.message}"
)
}
}
}
