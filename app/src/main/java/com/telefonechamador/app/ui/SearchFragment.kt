package com.telefonechamador.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.telefonechamador.app.R
import com.telefonechamador.app.data.CallRepository
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val recycler = v.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val empty = v.findViewById<TextView>(R.id.empty)
        val edt = v.findViewById<EditText>(R.id.edt_query)
        val btn = v.findViewById<MaterialButton>(R.id.btn_search)
        val repo = CallRepository(requireContext())
        val adapter = SearchAdapter { row ->
            startActivity(
                Intent(requireContext(), CallDetailActivity::class.java)
                    .putExtra("callId", row.callId)
            )
        }
        recycler.adapter = adapter
        empty.text = "Busque por palavras ditas nas conversas transcritas."

        fun runSearch() {
            val q = edt.text.toString()
            if (q.isBlank()) return
            viewLifecycleOwner.lifecycleScope.launch {
                val results = repo.search(q)
                adapter.submitList(results)
                empty.isVisible = results.isEmpty()
                empty.text = "Nada encontrado para \"$q\"."
            }
        }

        btn.setOnClickListener { runSearch() }
        edt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
    }
}
