package dev.lemon.lemon_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.adapters.CheatAdapter
import dev.lemon.lemon_emu.databinding.DialogCheatsBinding
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.AtmosphereCheat
import dev.lemon.lemon_emu.utils.AtmosphereCheatDownloader
import dev.lemon.lemon_emu.utils.AtmosphereCheatHelper
import dev.lemon.lemon_emu.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class CheatsDialogFragment : DialogFragment() {

    private var _binding: DialogCheatsBinding? = null
    private val binding get() = _binding!!

    private lateinit var game: Game
    private var buildId: String = ""
    private var cheatsList = mutableListOf<AtmosphereCheat>()
    private var adapter: CheatAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCheatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Получаем Build ID из файла игры
        buildId = NativeLibrary.getGameBuildId(game.path).uppercase(Locale.ROOT).trim()
        binding.textBuildId.text = if (buildId.isNotEmpty()) {
            "Build ID: $buildId"
        } else {
            "Build ID: не определен"
        }

        binding.recyclerCheats.layoutManager = LinearLayoutManager(requireContext())

        loadCheats()

        binding.buttonDownload.setOnClickListener {
            downloadCheats()
        }

        binding.buttonClose.setOnClickListener {
            dismiss()
        }
    }

    private fun getCheatsFile(): File {
        val titleIdHex = String.format("%016X", game.titleId)
        val cheatsDir = File(FileUtil.getDirectoryPath(), "load/mods/$titleIdHex/cheats")
        return File(cheatsDir, "$buildId.txt")
    }

    private fun getTogglesFile(): File {
        val titleIdHex = String.format("%016X", game.titleId)
        val cheatsDir = File(FileUtil.getDirectoryPath(), "load/mods/$titleIdHex/cheats")
        return File(cheatsDir, "toggles.txt")
    }

    private fun loadCheats() {
        val cheatFile = getCheatsFile()
        val togglesFile = getTogglesFile()

        if (!cheatFile.exists() || buildId.isEmpty()) {
            cheatsList.clear()
            updateListVisibility()
            return
        }

        val toggles = AtmosphereCheatHelper.readToggles(togglesFile)
        val parsedCheats = AtmosphereCheatHelper.parseCheatText(cheatFile.readText(), toggles)

        cheatsList.clear()
        cheatsList.addAll(parsedCheats)

        adapter = CheatAdapter(cheatsList) {
            saveToggles()
        }
        binding.recyclerCheats.adapter = adapter

        updateListVisibility()
    }

    private fun saveToggles() {
        val togglesFile = getTogglesFile()
        val enabledNames = cheatsList.filter { it.isEnabled }.map { it.name }.toSet()
        AtmosphereCheatHelper.saveToggles(togglesFile, enabledNames)
    }

    private fun updateListVisibility() {
        if (cheatsList.isEmpty()) {
            binding.recyclerCheats.visibility = View.GONE
            binding.textEmpty.visibility = View.VISIBLE
        } else {
            binding.recyclerCheats.visibility = View.VISIBLE
            binding.textEmpty.visibility = View.GONE
        }
    }

    private fun downloadCheats() {
        if (buildId.isEmpty()) {
            Toast.makeText(requireContext(), "Build ID игры не определен", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressDownload.visibility = View.VISIBLE
        binding.buttonDownload.isEnabled = false

        val titleIdHex = String.format("%016X", game.titleId)
        val baseDir = FileUtil.getDirectoryPath()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = AtmosphereCheatDownloader.downloadAndInstallCheats(
                titleIdHex = titleIdHex,
                buildIdHex = buildId,
                baseDirectory = baseDir
            )

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                binding.progressDownload.visibility = View.GONE
                binding.buttonDownload.isEnabled = true

                if (result.success) {
                    Toast.makeText(
                        requireContext(),
                        "Успешно! Всего кодов: ${result.totalCheatsCount} (графика/FPS: ${result.graphicsCheatsCount})",
                        Toast.LENGTH_LONG
                    ).show()
                    loadCheats()
                } else {
                    Toast.makeText(
                        requireContext(),
                        result.errorMessage ?: "Ошибка загрузки читов",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CheatsDialogFragment"

        fun newInstance(game: Game): CheatsDialogFragment {
            return CheatsDialogFragment().apply {
                this.game = game
            }
        }
    }
}
