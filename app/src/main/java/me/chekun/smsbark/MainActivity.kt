package me.chekun.smsbark

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import me.chekun.smsbark.ui.theme.SmsBarkTheme
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val REQUIRED_SMS_PERMISSIONS = arrayOf(
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_SMS,
    Manifest.permission.SEND_SMS
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val composeTarget = extractComposeTarget(intent)
        setContent {
            SmsBarkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(initialComposeTarget = composeTarget)
                }
            }
        }
    }

    // singleTask 模式下应用已在前台时，系统会走这里而不是 onCreate
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (extractComposeTarget(intent) != null) {
            recreate()
        }
    }
}

/** 从系统发短信 Intent（分享、联系人拨号盘快捷回复等）中解析出收件人和预填正文 */
private fun extractComposeTarget(intent: Intent?): Pair<String, String>? {
    if (intent == null) return null
    return when (intent.action) {
        Intent.ACTION_SENDTO -> {
            val address = intent.data?.schemeSpecificPart ?: return null
            if (address.isBlank()) return null
            address to (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
        }
        // ACTION_SEND 没有明确收件人，跳转到收件箱由用户自行选择会话
        else -> null
    }
}

@Composable
fun AppNavigation(initialComposeTarget: Pair<String, String>? = null) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "inbox") {
        composable("inbox") { InboxScreen(navController) }
        composable("forward") { ForwardScreen(navController) }
        composable("config") { ConfigScreen(navController) }
        composable(
            route = "thread/{address}?body={body}",
            arguments = listOf(
                navArgument("address") { type = NavType.StringType },
                navArgument("body") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val address = URLDecoder.decode(
                backStackEntry.arguments?.getString("address") ?: "",
                "UTF-8"
            )
            val prefillBody = backStackEntry.arguments?.getString("body") ?: ""
            ThreadScreen(navController, address, prefillBody)
        }
    }

    LaunchedEffect(initialComposeTarget) {
        val (address, body) = initialComposeTarget ?: return@LaunchedEffect
        val encodedAddress = URLEncoder.encode(address, "UTF-8")
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        navController.navigate("thread/$encodedAddress?body=$encodedBody")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var successCount by remember { mutableIntStateOf(0) }
    var failureCount by remember { mutableIntStateOf(0) }
    var hasSmsPermission by remember { mutableStateOf(false) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(false) }
    var isDefaultSmsApp by remember { mutableStateOf(false) }

    // Launcher for SMS permissions (RECEIVE/READ/SEND all needed to act as default SMS app)
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results: Map<String, Boolean> ->
        hasSmsPermission = results.values.all { it }
    }

    // Launcher for default-SMS-app role request (result handled via onResume check)
    val defaultSmsRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultSmsApp = SmsRepository.isDefaultSmsApp(context)
    }

    fun requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                defaultSmsRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            defaultSmsRoleLauncher.launch(intent)
        }
    }

    // Function to check permissions
    fun checkPermissions() {
        hasSmsPermission = REQUIRED_SMS_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        isDefaultSmsApp = SmsRepository.isDefaultSmsApp(context)
    }

    // Initial check and refresh on resume
    DisposableEffect(lifecycleOwner) {
        checkPermissions() // Check immediately
        
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val stats = StatisticsManager.getTodayStats(context)
                successCount = stats.first
                failureCount = stats.second
                checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.forward_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("config") }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.config_title),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Permission Warnings
            if (!isDefaultSmsApp) {
                PermissionWarningCard(
                    title = stringResource(R.string.perm_default_sms_title),
                    description = stringResource(R.string.perm_default_sms_desc),
                    onClick = { requestDefaultSmsRole() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (!hasSmsPermission) {
                PermissionWarningCard(
                    title = stringResource(R.string.perm_sms_title),
                    description = stringResource(R.string.perm_sms_desc),
                    onClick = { smsPermissionLauncher.launch(REQUIRED_SMS_PERMISSIONS) }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (!isIgnoringBatteryOptimizations) {
                PermissionWarningCard(
                    title = stringResource(R.string.perm_battery_title),
                    description = stringResource(R.string.perm_battery_desc),
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Date Header
            val date = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault()).format(Date())
            Text(
                text = date,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            // Stats Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatsCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.stats_success),
                    count = successCount,
                    icon = Icons.Default.CheckCircle,
                    iconColor = Color(0xFF4CAF50), // Green
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
                StatsCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.stats_failure),
                    count = failureCount,
                    icon = Icons.Default.Warning,
                    iconColor = Color(0xFFF44336), // Red
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
            
            // Status Indicator
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        val isServiceReady = isDefaultSmsApp && hasSmsPermission
                        Text(
                            text = if (isServiceReady) stringResource(R.string.service_running) else stringResource(R.string.service_restricted),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (isServiceReady) stringResource(R.string.service_listening) else stringResource(R.string.service_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Version and GitHub Link
            val version = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName
            } catch (e: Exception) {
                "Unknown"
            }
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = String.format(stringResource(R.string.version_label), version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.github_repo),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://sms-bark.chekun.me"))
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
fun PermissionWarningCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun StatsCard(
    modifier: Modifier = Modifier,
    title: String,
    count: Int,
    icon: ImageVector,
    iconColor: Color,
    containerColor: Color
) {
    Card(
        modifier = modifier
            .height(160.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Column {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(navController: NavController) {
    val context = LocalContext.current
    var barkServer by remember { mutableStateOf(getConfig(context, "bark_server")) }
    var barkToken by remember { mutableStateOf(getConfig(context, "bark_token")) }
    var keywords by remember { mutableStateOf(getConfig(context, "keywords", "验证码,code,otp")) }
    val coroutineScope = rememberCoroutineScope()
    
    val testMsgTitle = stringResource(R.string.test_msg_title)
    val testMsgBody = stringResource(R.string.test_msg_body)
    val testMsgSuccess = stringResource(R.string.test_msg_success)
    val testMsgFail = stringResource(R.string.test_msg_fail)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.config_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                   IconButton(onClick = { navController.popBackStack() }) {
                        // Intentionally left blank for cleaner UI
                   }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Padding(padding = 16.dp) {
                    Text(
                        text = stringResource(R.string.config_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = barkServer,
                onValueChange = { barkServer = it },
                label = { Text(stringResource(R.string.config_server_label)) },
                placeholder = { Text(stringResource(R.string.config_server_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = barkToken,
                onValueChange = { barkToken = it },
                label = { Text(stringResource(R.string.config_token_label)) },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = { Text(stringResource(R.string.config_keywords_label)) },
                placeholder = { Text(stringResource(R.string.config_keywords_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        val success = BarkSender.send(
                            context = context,
                            barkServer = barkServer,
                            barkToken = barkToken,
                            title = testMsgTitle,
                            body = testMsgBody,
                            shouldUpdateStats = false // 测试消息不计入统计
                        )
                        val message = if (success) testMsgSuccess else testMsgFail
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.config_test_send), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = {
                    saveConfig(context, barkServer, barkToken, keywords)
                    navController.popBackStack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.config_save), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var conversations by remember { mutableStateOf(listOf<SmsRepository.Conversation>()) }
    var isDefaultSmsApp by remember { mutableStateOf(false) }

    fun reload() {
        isDefaultSmsApp = SmsRepository.isDefaultSmsApp(context)
        conversations = if (isDefaultSmsApp) SmsRepository.getConversations(context) else emptyList()
    }

    DisposableEffect(lifecycleOwner) {
        reload()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = { navController.navigate("forward") }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.forward_title),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (!isDefaultSmsApp) {
            InboxSetupPrompt(
                modifier = Modifier.padding(innerPadding),
                onSetupClick = { navController.navigate("forward") }
            )
        } else if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.inbox_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                items(conversations) { conversation ->
                    ConversationRow(conversation) {
                        val encodedAddress = URLEncoder.encode(conversation.address, "UTF-8")
                        navController.navigate("thread/$encodedAddress")
                    }
                }
            }
        }
    }
}

@Composable
fun InboxSetupPrompt(modifier: Modifier = Modifier, onSetupClick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.inbox_setup_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.inbox_setup_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onSetupClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.inbox_setup_button))
        }
    }
}

@Composable
fun ConversationRow(conversation: SmsRepository.Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.address,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = conversation.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(navController: NavController, address: String, prefillBody: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(listOf<SmsRepository.SmsMessage>()) }
    var inputText by remember { mutableStateOf(prefillBody) }
    var threadId by remember { mutableStateOf<Long?>(null) }

    fun reload() {
        val id = Telephony.Threads.getOrCreateThreadId(context, address)
        threadId = id
        messages = SmsRepository.getMessages(context, id)
    }

    LaunchedEffect(address) { reload() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(address, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { message ->
                    MessageBubble(message)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.thread_input_placeholder)) },
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val body = inputText
                        if (body.isNotBlank()) {
                            coroutineScope.launch {
                                SmsRepository.sendSms(context, address, body)
                                inputText = ""
                                reload()
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.thread_send),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: SmsRepository.SmsMessage) {
    val alignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = message.body,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun Padding(padding: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(padding)) {
        content()
    }
}

private fun getConfig(context: Context, key: String, defaultValue: String = ""): String {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getString(key, defaultValue) ?: defaultValue
}

private fun saveConfig(context: Context, barkServer: String, barkToken: String, keywords: String) {
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.putString("bark_server", barkServer)
    editor.putString("bark_token", barkToken)
    editor.putString("keywords", keywords)
    editor.apply()
}
