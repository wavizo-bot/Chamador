package com.telefonechamador.app.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.telefonechamador.app.R
import com.telefonechamador.app.data.CallRepository
import kotlinx.coroutines.launch

class BlockedNumbersActivity : AppCompatActivity() {

    private val repo by lazy { CallRepository(this) }
    private val adapter = BlockedAdapter { item ->
        lifecycleScope.launch { repo.unblock(item.number) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)
        findViewById<MaterialToolbar>(R.id.toolbar_detail).setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val empty = findViewById<TextView>(R.id.empty)
        val edt = findViewById<EditText>(R.id.edt_number)
        findViewById<MaterialButton>(R.id.btn_add).setOnClickListener {
            val n = edt.text.toString().trim()
            if (n.isNotBlank()) {
                lifecycleScope.launch {
                    repo.block(n)
                    edt.setText("")
                }
            }
        }

        lifecycleScope.launch {
            repo.observeBlocked().collect { list ->
                adapter.submitList(list)
                empty.isVisible = list.isEmpty()
                empty.text = "Nenhum número bloqueado.\n\nQuando alguém da lista ligar, o toque é silenciado e a chamada é rejeitada."
            }
        }
    }
}
