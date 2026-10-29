package dev.lemon.lemon_emu.utils
​import java.io.File
​data class AtmosphereCheat(
val name: String,
val lines: List<String>,
var isEnabled: Boolean = false,
val isGraphicsMod: Boolean = false
) {
val opcodeCount: Int get() = lines.count { it.trim().isNotEmpty() }
}
​object AtmosphereCheatHelper {
private const val MAX_OPCODES = 0x400
​fun canEnableMore(currentCheats: List<AtmosphereCheat>, candidate: AtmosphereCheat): Boolean {
val currentActiveOpcodes = currentCheats.filter { it.isEnabled }.sumOf { it.opcodeCount }
return (currentActiveOpcodes + candidate.opcodeCount) <= MAX_OPCODES
}
​fun parseCheatText(text: String, enabledNames: Set<String>): List<AtmosphereCheat> {
val result = mutableListOf<AtmosphereCheat>()
var currentName: String? = null
val currentLines = mutableListOf<String>()
​text.lineSequence().forEach { rawLine ->
val line = rawLine.trim()
if (line.startsWith("{") && line.endsWith("}")) {
currentName?.let { name ->
result.add(AtmosphereCheat(name, ArrayList(currentLines), enabledNames.contains(name), isGraphicsTitle(name)))
currentLines.clear()
}
currentName = line.removeSurrounding("{", "}").trim()
} else if (line.isNotEmpty()) {
currentLines.add(line)
}
}
​currentName?.let { name ->
result.add(AtmosphereCheat(name, ArrayList(currentLines), enabledNames.contains(name), isGraphicsTitle(name)))
}
​return result
}
​fun isGraphicsTitle(name: String): Boolean {
val upper = name.uppercase()
return upper.contains("FPS") || upper.contains("60") || upper.contains("RESOLUTION") ||
upper.contains("DYNAMIC") || upper.contains("GRAPHIC") || upper.contains("VSYNC")
}
​fun readToggles(file: File): Set<String> {
if (!file.exists()) return emptySet()
return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
​fun saveToggles(file: File, enabledNames: Set<String>) {
file.parentFile?.mkdirs()
file.writeText(enabledNames.joinToString("\n"))
}
}
