// SPDX-FileCopyrightText: Copyright 2026 Lemon-Project
// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.lemon.lemon_emu.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.lemon.lemon_emu.NativeLibrary
import dev.lemon.lemon_emu.R
import dev.lemon.lemon_emu.adapters.CheatAdapter
import dev.lemon.lemon_emu.databinding.DialogCheatsBinding
import dev.lemon.lemon_emu.model.Game
import dev.lemon.lemon_emu.utils.AtmosphereCheat
import dev.lemon.lemon_emu.utils.AtmosphereCheatDownloader
import dev.lemon.lemon_emu.utils.AtmosphereCheatHelper
import dev.lemon.lemon_emu.utils.DirectoryInitialization
import dev.lemon.lemon_emu.utils.Log
import kotlinx.coroutines.launch
import java.io.File

class CheatsDialogFragment : DialogFragment() {

private var _binding: DialogCheatsBinding? = null
private val binding get() = _binding!!

private lateinit var currentGame: Game
private var buildId: String = ""
private val cheatList = mutableListOf<AtmosphereCheat>()
private var adapter: CheatAdapter? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val args = arguments ?: return
    val game = BundleCompat.getParcelable(args, ARG_GAME, Game::class.java)
    if (game == null) {
        dismiss()
        return
    }
    currentGame = game

    // Получаем актуальный Build ID исполняемого файла игры через нативную библиотеку
    val rawBuildId = NativeLibrary.getGameBuildId(currentGame.path)
    buildId = if (rawBuildId.length >= 16) {
        rawBuildId.substring(0, 16).uppercase()
    } else {
        rawBuildId.uppercase()
    }
}

override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    _binding = DialogCheatsBinding.inflate(layoutInflater)

    binding.textGameTitle.text = currentGame.title
    binding.textBuildId.text = if (buildId.isNotEmpty()) {
        getString(R.string.cheat_build_id, buildId)
    } else {
        getString(R.string.cheat_build_id_unknown)
    }

    binding.recyclerCheats.layoutManager = LinearLayoutManager(requireContext())
    adapter = CheatAdapter(cheatList) {
        saveToggles()
    }
    binding.recyclerCheats.adapter = adapter

    binding.buttonDownload.setOnClickListener {
        downloadCheats()
    }

    loadCheats()

    return MaterialAlertDialogBuilder(requireContext())
        .setView(binding.root)
        .setPositiveButton(android.R.string.ok) { dialog, _ ->
            dialog.dismiss()
        }
        .create()
}

private fun getCheatsFile(): File {
    val titleIdHex = currentGame.programIdHex
    val cheatsDir = File(DirectoryInitialization.userDirectory, "load/mods/$titleIdHex/cheats")
    return File(cheatsDir, "$buildId.txt")
}

private fun getTogglesFile(): File {
    val titleIdHex = currentGame.programIdHex
    val cheatsDir = File(DirectoryInitialization.userDirectory, "load/mods/$titleIdHex/cheats")
    return File(cheatsDir, "toggles.txt")
}

private fun loadCheats() {
    if (buildId.isEmpty()) {
        updateEmptyState(true)
        return
    }

    val cheatsFile = getCheatsFile()
    if (!cheatsFile.exists()) {
        updateEmptyState(true)
        return
    }

    val togglesFile = getTogglesFile()
    val enabledNames = AtmosphereCheatHelper.readToggles(togglesFile)

    val text = cheatsFile.readText()
    val parsed = AtmosphereCheatHelper.parseCheatText(text, enabledNames)

    cheatList.clear()
    cheatList.addAll(parsed)
    adapter?.notifyDataSetChanged()

    updateEmptyState(cheatList.isEmpty())
}

private fun saveToggles() {
    val togglesFile = getTogglesFile()
    val enabledNames = cheatList.filter { it.isEnabled }.map { it.name }.toSet()
    AtmosphereCheatHelper.saveToggles(togglesFile, enabledNames)

    // Перезагружаем читы в активном эмуляторе, если игра уже запущена
    if (NativeLibrary.isRunning()) {
        NativeLibrary.reloadCheats(currentGame.programIdHex)
    }
}

private fun downloadCheats() {
    if (buildId.isEmpty()) {
        Toast.makeText(requireContext(), R.string.cheat_build_id_unknown, Toast.LENGTH_SHORT).show()
        return
    }

    binding.progressDownload.visibility = View.VISIBLE
    binding.buttonDownload.isEnabled = false

    lifecycleScope.launch {
        val baseDir = DirectoryInitialization.userDirectory
        val result = AtmosphereCheatDownloader.downloadAndInstallCheats(
            titleIdHex = currentGame.programIdHex,
            buildIdHex = buildId,
            baseDirectory = baseDir
        )

        binding.progressDownload.visibility = View.GONE
        binding.buttonDownload.isEnabled = true

        if (result.success) {
            Toast.makeText(
                requireContext(),
                "Загружено: ${result.totalCheatsCount} (из них графических: ${result.graphicsCheatsCount})",
                Toast.LENGTH_LONG
            ).show()
            loadCheats()
        } else {
            Toast.makeText(
                requireContext(),
                result.errorMessage ?: "Не удалось загрузить читы",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

private fun updateEmptyState(isEmpty: Boolean) {
    binding.textEmptyCheats.visibility = if (isEmpty) View.VISIBLE else View.GONE
    binding.recyclerCheats.visibility = if (isEmpty) View.GONE else View.VISIBLE
}

override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
}

companion object {
    const val TAG = "CheatsDialogFragment"
    private const val ARG_GAME = "game"

    fun newInstance(game: Game): CheatsDialogFragment {
        return CheatsDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_GAME, game)
            }
        }
    }
}


}
