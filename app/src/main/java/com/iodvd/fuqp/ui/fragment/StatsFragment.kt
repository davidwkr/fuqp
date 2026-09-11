package com.iodvd.fuqp.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import dev.androidbroadcast.vbpd.viewBinding
import com.iodvd.fuqp.common.FilterHolder
import com.iodvd.fuqp.service.ServiceClient
import com.iodvd.fuqp.ui.util.setupToolbar
import com.iodvd.fuqp.ui.util.showToast
import com.iodvd.fuqp.util.PackageHelper
import kotlinx.coroutines.launch
import com.iodvd.fuqp.R
import com.iodvd.fuqp.databinding.FragmentLogsBinding
import com.iodvd.fuqp.ui.adapter.StatAdapter

class StatsFragment(
    private val loadingIndicator: View,
    private val toolbar: Toolbar,
) : Fragment(R.layout.fragment_logs) {

    private val binding by viewBinding(FragmentLogsBinding::bind)
    private val adapter by lazy { StatAdapter {
        lifecycleScope.launch {
            PackageHelper.isRefreshing
                .flowWithLifecycle(lifecycle)
                .collect { isRefreshing ->
                    if (!isRefreshing && it.wasRefreshing) {
                        it.wasRefreshing = false

                        updateLogs()
                    }
                }
        }
    } }
    private var statCache: String? = null

    private fun updateLogs() {
        loadingIndicator.isVisible = PackageHelper.refreshing

        lifecycleScope.launch {
            statCache = runCatching { ServiceClient.detailedFilterStats }.getOrNull()
            if (statCache == null) {
                binding.serviceOff.visibility = View.VISIBLE
            } else {
                binding.serviceOff.visibility = View.GONE

                val stats = FilterHolder.parse(statCache!!)

                fun getTotalCount(key: String) = stats.filterCounts[key]!!.totalCount

                val countsKeys = stats.filterCounts.keys.sortedWith { key1, key2 ->
                    getTotalCount(key1).compareTo(getTotalCount(key2))
                }.asReversed()

                for (key in countsKeys) {
                    adapter.addOrUpdateEntry(
                        key,
                        stats.filterCounts[key]!!
                    )
                }

                adapter.clearEntriesIfNotFound(countsKeys)
            }
        }
    }

    private fun onMenuOptionSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_refresh -> updateLogs()
            R.id.menu_delete -> {
                ServiceClient.clearFilterStats()
                showToast(android.R.string.ok)
                updateLogs()
            }
            R.id.menu_info -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/davidwkr/fuqp/wiki/About-FUQP#filter-logs-categories".toUri()
                })
            }
            // TODO: Add other options when required
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.layoutManager = LinearLayoutManager(context)
        binding.list.adapter = adapter
        binding.list.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        updateLogs()
    }

    override fun onResume() {
        super.onResume()

        setupToolbar(
            toolbar,
            title = getString(R.string.title_filter_logs),
            menuRes = R.menu.menu_stats,
            onMenuOptionSelected = this::onMenuOptionSelected,
        )
    }
}
