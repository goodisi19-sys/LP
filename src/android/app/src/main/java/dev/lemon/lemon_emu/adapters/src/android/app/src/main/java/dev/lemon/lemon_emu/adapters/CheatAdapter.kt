package dev.lemon.lemon_emu.adapters
​import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import dev.lemon.lemon_emu.databinding.ItemCheatResultBinding
import dev.lemon.lemon_emu.utils.AtmosphereCheat
import dev.lemon.lemon_emu.utils.AtmosphereCheatHelper
​class CheatAdapter(
private val cheats: List<AtmosphereCheat>,
private val onToggleChanged: () -> Unit
) : RecyclerView.Adapter<CheatAdapter.CheatViewHolder>() {
​inner class CheatViewHolder(val binding: ItemCheatResultBinding) :
RecyclerView.ViewHolder(binding.root)
​override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheatViewHolder {
val binding = ItemCheatResultBinding.inflate(
LayoutInflater.from(parent.context),
parent,
false
)
return CheatViewHolder(binding)
}
​override fun getItemCount(): Int = cheats.size
​override fun onBindViewHolder(holder: CheatViewHolder, position: Int) {
val cheat = cheats[position]
holder.binding.cheatName.text = cheat.name
holder.binding.cheatCheckbox.setOnCheckedChangeListener(null)
holder.binding.cheatCheckbox.isChecked = cheat.isEnabled
​holder.binding.cheatCheckbox.setOnCheckedChangeListener { buttonView, isChecked ->
if (isChecked) {
if (!AtmosphereCheatHelper.canEnableMore(cheats, cheat)) {
buttonView.isChecked = false
Toast.makeText(
buttonView.context,
"Превышен лимит Cheat VM (максимум 0x400 опкодов)!",
Toast.LENGTH_SHORT
).show()
return@setOnCheckedChangeListener
}
}
cheat.isEnabled = isChecked
onToggleChanged()
}
}
}
