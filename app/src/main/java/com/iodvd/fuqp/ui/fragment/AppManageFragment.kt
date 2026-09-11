package com.iodvd.fuqp.ui.fragment

import com.iodvd.fuqp.service.ConfigManager
import com.iodvd.fuqp.ui.adapter.AppManageAdapter
import com.iodvd.fuqp.ui.util.navigate
import com.iodvd.fuqp.util.PackageHelper
import com.iodvd.fuqp.R
import com.iodvd.fuqp.ui.fragment.AppSettingsV2FragmentArgs

class AppManageFragment : AppSelectFragment() {

    override val firstComparator: Comparator<String> = Comparator.comparing(ConfigManager::isHideEnabled).reversed()

    override val adapter = AppManageAdapter {
        if (PackageHelper.exists(it)) {
            val args = AppSettingsV2FragmentArgs(it)
            navigate(R.id.nav_app_settings, args.toBundle())
        }
    }
}
