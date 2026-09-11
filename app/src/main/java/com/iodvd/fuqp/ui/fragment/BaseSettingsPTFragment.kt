package com.iodvd.fuqp.ui.fragment

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import dev.androidbroadcast.vbpd.viewBinding
import com.iodvd.fuqp.ui.util.navController
import com.iodvd.fuqp.ui.util.setEdge2EdgeFlags
import com.iodvd.fuqp.ui.util.setupToolbar
import com.iodvd.fuqp.R
import com.iodvd.fuqp.databinding.FragmentSettingsPtBaseBinding
import com.iodvd.fuqp.ui.adapter.BaseSettingsPTAdapter

abstract class BaseSettingsPTFragment : Fragment(R.layout.fragment_settings_pt_base) {
    val binding by viewBinding(FragmentSettingsPtBaseBinding::bind)

    abstract val adapter: BaseSettingsPTAdapter

    abstract val title: String?

    internal open fun onBack() {
        navController.navigateUp()
    }

    /**
     * first - menuRes, second - onMenuOptionSelected
     */
    abstract val menu: Pair<Int, ((MenuItem) -> Unit)>?

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { onBack() }

        setupToolbar(
            toolbar = binding.toolbar,
            title = title ?: "",
            navigationIcon = R.drawable.baseline_arrow_back_24,
            navigationOnClick = { onBack() },
            menuRes = menu?.first,
            onMenuOptionSelected = menu?.second,
        )

        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter

        setEdge2EdgeFlags(binding.root)
    }
}
