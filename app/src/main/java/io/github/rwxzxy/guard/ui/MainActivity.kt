@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.rwxzxy.guard.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rwxzxy.guard.container
import io.github.rwxzxy.guard.ui.screens.AccessScreen
import io.github.rwxzxy.guard.ui.screens.AppDetailScreen
import io.github.rwxzxy.guard.ui.screens.AppsScreen
import io.github.rwxzxy.guard.ui.screens.LogScreen
import io.github.rwxzxy.guard.ui.screens.PolicyScreen
import io.github.rwxzxy.guard.ui.theme.GuardTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** 通知点击时要切换到的页面。 */
    private val tabRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) tabRequests.value = intent?.getStringExtra(EXTRA_TAB)
        val appContainer = applicationContext.container
        setContent {
            GuardTheme {
                val vm: MainViewModel = viewModel { MainViewModel(appContainer) }
                val request by tabRequests.collectAsStateWithLifecycle()
                LaunchedEffect(request) {
                    request?.let {
                        vm.tab = tabFromExtra(it)
                        vm.detailPackage = null
                        tabRequests.value = null
                    }
                }
                GuardApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tabRequests.value = it }
    }

    override fun onResume() {
        super.onResume()
        applicationContext.container.shizuku.refresh()
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_APPS = "apps"
        const val TAB_ACCESS = "access"
        const val TAB_POLICY = "policy"
        const val TAB_LOG = "log"

        fun tabFromExtra(value: String): Tab = when (value) {
            TAB_ACCESS -> Tab.ACCESS
            TAB_POLICY -> Tab.POLICY
            TAB_LOG -> Tab.LOG
            else -> Tab.APPS
        }
    }
}

@Composable
private fun GuardApp(vm: MainViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.message("未授予通知权限：自动收紧和风险提醒将无法通知你")
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val detail = vm.detailPackage
    BackHandler(enabled = detail != null) { vm.detailPackage = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            detail != null -> rows.firstOrNull { it.packageName == detail }?.facts?.label ?: "应用详情"
                            vm.tab == Tab.APPS -> "隐私守卫"
                            vm.tab == Tab.ACCESS -> "访问记录"
                            vm.tab == Tab.POLICY -> "自动收紧"
                            else -> "操作日志"
                        },
                    )
                },
                navigationIcon = {
                    if (detail != null) {
                        IconButton(onClick = { vm.detailPackage = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (detail == null && vm.tab == Tab.APPS) {
                        IconButton(onClick = { vm.refreshAll() }, enabled = !loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (detail == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = vm.tab == Tab.APPS,
                        onClick = { vm.tab = Tab.APPS },
                        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                        label = { Text("应用") },
                    )
                    NavigationBarItem(
                        selected = vm.tab == Tab.ACCESS,
                        onClick = { vm.tab = Tab.ACCESS },
                        icon = { Icon(Icons.Filled.Timeline, contentDescription = null) },
                        label = { Text("访问记录") },
                    )
                    NavigationBarItem(
                        selected = vm.tab == Tab.POLICY,
                        onClick = { vm.tab = Tab.POLICY },
                        icon = { Icon(Icons.Filled.Policy, contentDescription = null) },
                        label = { Text("自动收紧") },
                    )
                    NavigationBarItem(
                        selected = vm.tab == Tab.LOG,
                        onClick = { vm.tab = Tab.LOG },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        label = { Text("日志") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (detail != null) {
                AppDetailScreen(vm = vm, packageName = detail)
            } else {
                when (vm.tab) {
                    Tab.APPS -> AppsScreen(vm = vm, onOpen = { vm.detailPackage = it })
                    Tab.ACCESS -> AccessScreen(vm = vm, onOpen = { vm.detailPackage = it })
                    Tab.POLICY -> PolicyScreen(vm = vm)
                    Tab.LOG -> LogScreen(vm = vm, onOpen = { vm.detailPackage = it })
                }
            }
        }
    }
}
