package com.telefonechamador.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.telefonechamador.app.R
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.util.CallUtils
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private var imported = false

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val recycler = v.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val empty = v.findViewById<TextView>(R.id.empty)
        val btnRedial = v.findViewById<MaterialButton>(R.id.btn_redial)
        val repo = CallRepository(requireContext())
        val adapter = CallsAdapter(
            onClick = { c ->
                startActivity(
                    Intent(requireContext(), CallDetailActivity::class.java)
                        .putExtra("callId", c.id)
                )
            },
            onFav = { c ->
                viewLifecycleOwner.lifecycleScope.launch { repo.setFavorite(c.id, !c.favorite) }
            },
        )
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeCalls().collect { list ->
                adapter.submitList(list)
                empty.isVisible = list.isEmpty()
                // Atalho: refazer a última ligação feita
                val lastOut = list.firstOrNull { it.direction == "out" && it.number.isNotBlank() }
                if (lastOut != null) {
                    btnRedial.isVisible = true
                    val label = lastOut.contactName?.takeIf { it.isNotBlank() } ?: lastOut.number
                    btnRedial.text = "↻  Refazer ligação: $label"
                    btnRedial.setOnClickListener {
                        CallUtils.placeCall(requireContext(), lastOut.number)
                    }
                } else {
                    btnRedial.isVisible = false
                }
                if (list.isEmpty() && !imported) {
                    imported = true
                    launch { repo.importSystemLog() }
                }
            }
        }
    }
}
