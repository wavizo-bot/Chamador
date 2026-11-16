package com.telefonechamador.app.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telefonechamador.app.R
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.util.CallUtils
import kotlinx.coroutines.launch

class DialpadDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val v = layoutInflater.inflate(R.layout.dialog_dialpad, null)
        val edt = v.findViewById<EditText>(R.id.edt_number)
        arguments?.getString("number")?.let { edt.setText(it) }

        // Sem número digitado: sugere o último ligado (refazer ligação)
        if (edt.text.isNullOrBlank()) {
            lifecycleScope.launch {
                val last = try { CallRepository(requireContext()).lastOutgoingNumber() } catch (_: Throwable) { null }
                if (last != null && edt.text.isNullOrBlank()) edt.setText(last)
            }
        }

        val ids = intArrayOf(
            R.id.btn_1, R.id.btn_2, R.id.btn_3,
            R.id.btn_4, R.id.btn_5, R.id.btn_6,
            R.id.btn_7, R.id.btn_8, R.id.btn_9,
            R.id.btn_star, R.id.btn_0, R.id.btn_hash,
        )
        val chars = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        for (i in ids.indices) {
            v.findViewById<View>(ids[i]).setOnClickListener { edt.append(chars[i]) }
        }
        v.findViewById<View>(R.id.btn_plus).setOnClickListener {
            if (edt.text.isEmpty()) edt.append("+")
        }
        v.findViewById<View>(R.id.btn_back).setOnClickListener {
            val s = edt.text.toString()
            if (s.isNotEmpty()) edt.setText(s.dropLast(1))
        }
        v.findViewById<View>(R.id.btn_call).setOnClickListener {
            CallUtils.placeCall(requireContext(), edt.text.toString())
            dismiss()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discar")
            .setView(v)
            .setNegativeButton("Fechar", null)
            .create()
    }
}
