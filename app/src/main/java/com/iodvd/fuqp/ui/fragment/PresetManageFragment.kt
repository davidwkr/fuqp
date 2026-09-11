package com.iodvd.fuqp.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.clearFragmentResultListener
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.androidbroadcast.vbpd.viewBinding
import com.iodvd.fuqp.MyApp.Companion.fuqpApp
import com.iodvd.fuqp.service.ConfigManager
import com.iodvd.fuqp.service.ServiceClient
import com.iodvd.fuqp.ui.fragment.ScopeFragmentArgs
import com.iodvd.fuqp.ui.util.navController
import com.iodvd.fuqp.ui.util.navigate
import com.iodvd.fuqp.ui.util.setEdge2EdgeFlags
import com.iodvd.fuqp.ui.util.setupToolbar
import kotlinx.coroutines.launch
import com.iodvd.fuqp.R
import com.iodvd.fuqp.databinding.FragmentPresetManageBinding
import com.iodvd.fuqp.ui.adapter.AppPresetListAdapter

class PresetManageFragment : Fragment(R.layout.fragment_preset_manage) {

    private val binding by viewBinding(FragmentPresetManageBinding::bind)
    private val adapter by lazy {
        AppPresetListAdapter(requireContext(), this::navigateToPreset)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupToolbar(
            toolbar = binding.toolbar,
            title = getString(R.string.title_preset_manage),
            navigationIcon = R.drawable.baseline_arrow_back_24,
            navigationOnClick = { navController.navigateUp() },
            menuRes = R.menu.menu_preset_manage,
            onMenuOptionSelected = {
                val progressDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.refresh)
                    .setView(R.layout.dialog_loading)
                    .setCancelable(false)
                    .create()

                progressDialog.show()

                fuqpApp.globalScope.launch {
                    ServiceClient.reloadPresetsFromScratch()

                    progressDialog.dismiss()
                }
            },
        )
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        binding.presetList.layoutManager = LinearLayoutManager(context)
        binding.presetList.adapter = adapter

        setEdge2EdgeFlags(binding.root)
    }

    private fun navigateToPreset(presetInfo: ConfigManager.PresetInfo) {
        when (presetInfo.type!!) {
            ConfigManager.PTType.APP -> {
                val args = AppPresetFragmentArgs(presetInfo.name, presetInfo.translation)
                navigate(R.id.nav_preset_inner_manage, args.toBundle())
            }
            ConfigManager.PTType.SETTINGS -> {
                val args = SettingsPresetFragmentArgs(presetInfo.name, presetInfo.translation)
                navigate(R.id.nav_settings_preset_inner_manage, args.toBundle())
            }
            ConfigManager.PTType.IGNORED_APPS -> {
                setFragmentResultListener("app_select") { _, bundle ->
                    ConfigManager.ignoredPackagesForPresets = bundle.getStringArrayList("checked")!!.toSet()
                    clearFragmentResultListener("app_select")
                }
                val args = ScopeFragmentArgs(
                    filterOnlyEnabled = false,
                    checked = ConfigManager.ignoredPackagesForPresets.toTypedArray(),
                )
                navigate(R.id.nav_scope, args.toBundle())
            }
        }
    }
}
