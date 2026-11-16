package com.telefonechamador.app.ui

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telefonechamador.app.R
import com.telefonechamador.app.util.CallUtils
import com.telefonechamador.app.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsFragment : Fragment(R.layout.fragment_contacts) {

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val recycler = v.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val empty = v.findViewById<TextView>(R.id.empty)
        val adapter = ContactsAdapter { row -> CallUtils.placeCall(requireContext(), row.number) }
        recycler.adapter = adapter

        if (!Permissions.granted(requireContext(), Manifest.permission.READ_CONTACTS)) {
            empty.text = "Conceda a permissão de contatos para ver a lista aqui."
            empty.isVisible = true
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { loadContacts() }
            adapter.submitList(rows)
            empty.isVisible = rows.isEmpty()
            if (rows.isEmpty()) empty.text = "Nenhum contato com telefone encontrado."
        }
    }

    private fun loadContacts(): List<ContactRow> = try {
        val out = LinkedHashMap<String, ContactRow>()
        requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(0) ?: continue
                val raw = cur.getString(1) ?: continue
                val number = FmtNormalize(raw)
                if (number.isBlank()) continue
                val key = FmtKey(number)
                if (!out.containsKey(key)) out[key] = ContactRow(name, number)
            }
        }
        out.values.sortedBy { it.name.lowercase() }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun FmtNormalize(s: String): String = s.trim()
    private fun FmtKey(s: String): String = s.filter { it.isDigit() }
}
