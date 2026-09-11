package com.iodvd.fuqp.ui.adapter

import android.widget.Filter
import android.widget.Filterable
import androidx.recyclerview.widget.RecyclerView
import com.iodvd.fuqp.service.PrefManager
import com.iodvd.fuqp.ui.view.AppItemView
import com.iodvd.fuqp.util.PackageHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.iodvd.fuqp.BuildConfig

abstract class AppSelectAdapter(
    private val hideMyself: Boolean,
    private val firstFilter: ((String) -> Boolean)? = null,
) : RecyclerView.Adapter<AppSelectAdapter.ViewHolder>(), Filterable {

    abstract class ViewHolder(view: AppItemView) : RecyclerView.ViewHolder(view) {
        abstract fun bind(packageName: String)
    }

    private inner class AppFilter : Filter() {
        override fun performFiltering(constraint: CharSequence): FilterResults {
            return runBlocking {
                val constraintLowered = constraint.toString().trim().lowercase()
                val filteredList = PackageHelper.appList.first().filter {
                    if (firstFilter?.invoke(it) == false) return@filter false
                    if (!PrefManager.appFilter_showSystem && PackageHelper.isSystem(it)) return@filter false
                    if (it == BuildConfig.APPLICATION_ID && hideMyself) return@filter false
                    val label = PackageHelper.loadAppLabel(it)
                    label.lowercase().contains(constraintLowered) || it.lowercase().contains(constraintLowered)
                }

                FilterResults().also { it.values = filteredList }
            }
        }

        @Suppress("UNCHECKED_CAST", "NotifyDataSetChanged")
        override fun publishResults(constraint: CharSequence, results: FilterResults) {
            val values = results.values
            if (values != null) {
                filteredList = values as List<String>
                notifyDataSetChanged()
            }
        }
    }

    private val mFilter = AppFilter()

    protected var filteredList: List<String> = listOf()

    override fun getItemCount() = filteredList.size

    override fun getItemId(position: Int) = filteredList[position].hashCode().toLong()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(filteredList[position])

    override fun getFilter(): Filter = mFilter
}
