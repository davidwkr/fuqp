package com.iodvd.fuqp.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.iodvd.fuqp.MyApp.Companion.fuqpApp
import com.iodvd.fuqp.service.PrefManager
import com.iodvd.fuqp.ui.fragment.AppSelectFragment
import com.iodvd.fuqp.util.PackageHelper
import com.iodvd.fuqp.util.PackageHelper.Comparators
import kotlinx.coroutines.launch
import com.iodvd.fuqp.ui.adapter.AppPresetAdapter

class AppPresetFragment() : AppSelectFragment() {

    override val firstComparator: Comparator<String> = Comparator.comparing(PackageHelper::exists).reversed()

    private val args by lazy { navArgs<AppPresetFragmentArgs>() }

    override val adapter by lazy { AppPresetAdapter(args.value.name) }

    override fun getFragmentTitle() = args.value.title

    override fun sortList() {
        fuqpApp.globalScope.launch {
            sortPresetList()

            lifecycleScope.launch {
                applyFilter()
            }
        }
    }

    fun sortPresetList() {
        var comparator = when (PrefManager.appFilter_sortMethod) {
            PrefManager.SortMethod.BY_LABEL -> Comparators.byLabel
            PrefManager.SortMethod.BY_PACKAGE_NAME -> Comparators.byPackageName
            PrefManager.SortMethod.BY_INSTALL_TIME -> Comparators.byInstallTime
            PrefManager.SortMethod.BY_UPDATE_TIME -> Comparators.byUpdateTime
        }
        if (PrefManager.appFilter_reverseOrder) comparator = comparator.reversed()

        val packages = adapter.packages.sortedWith(firstComparator.then(comparator))

        lifecycleScope.launch {
            adapter.packages.clear()
            adapter.packages += packages
        }
    }

    override fun invalidateCache() {
        super.invalidateCache()
        adapter.updateList()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            binding.swipeRefresh.isRefreshing = true
            adapter.updateList()
            sortList()
        }
    }
}
