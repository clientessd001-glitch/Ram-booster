package com.rambooster.pro

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF2979FF),
                    background = Color(0xFF0B0F19),
                    surface = Color(0xFF151C2C)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RamBoosterApp(context = this)
                }
            }
        }
    }
}

// Estrutura com leitura real dupla (Sistema + Kernel /proc/meminfo)
data class RealHardwareRam(
    val commercialGb: Int,
    val totalRealMb: Long,
    val usedActiveMb: Long,     // Igual às Configurações do Android (desconta Cache)
    val availRealMb: Long,      // Livre + Cache reciclável
    val usedWithCacheMb: Long,  // Igual a outros apps Booster (Ativo + Cache)
    val cachedAndBuffersMb: Long,
    val freeRawMb: Long
)

data class AppProcessItem(
    val appName: String,
    val packageName: String
)

@Composable
fun RamBoosterApp(context: Context) {
    val scope = rememberCoroutineScope()

    var ramData by remember { mutableStateOf(readRealHardwareRam(context)) }
    var showRawCacheAsUsed by remember { mutableStateOf(false) }
    var isOptimizing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Leitura real do Kernel Linux (/proc/meminfo) ativa.") }

    // Estados do Auto RAM Booster em Segundos
    var autoBoosterEnabled by remember { mutableStateOf(false) }
    var intervalSeconds by remember { mutableIntStateOf(15) }
    var remainingSeconds by remember { mutableIntStateOf(15) }
    var autoCyclesCount by remember { mutableIntStateOf(0) }
    var totalFreedAccumulatedMb by remember { mutableLongStateOf(0L) }

    // Lista de apps do usuário
    var userApps by remember { mutableStateOf<List<AppProcessItem>>(emptyList()) }

    // Atualiza a leitura real da RAM a cada 2 segundos na tela
    LaunchedEffect(Unit) {
        userApps = loadUserInstalledApps(context)
        while (true) {
            if (!isOptimizing) {
                ramData = readRealHardwareRam(context)
            }
            delay(2000L)
        }
    }

    // Motor do Auto RAM Booster com temporizador em segundos
    LaunchedEffect(autoBoosterEnabled, intervalSeconds) {
        remainingSeconds = intervalSeconds
        while (autoBoosterEnabled) {
            delay(1000L)
            if (!isOptimizing) {
                remainingSeconds--
                if (remainingSeconds <= 0) {
                    isOptimizing = true
                    statusText = "Auto Booster: Limpando processos em 2º plano..."
                    val beforeAvail = readRealHardwareRam(context).availRealMb
                    val killed = executeRealRamClean(context)
                    delay(600L)
                    ramData = readRealHardwareRam(context)
                    val freed = (ramData.availRealMb - beforeAvail).coerceAtLeast(0L)

                    autoCyclesCount++
                    totalFreedAccumulatedMb += freed
                    statusText = "Ciclo #$autoCyclesCount concluído! +$freed MB livres ($killed apps limpos)."
                    isOptimizing = false
                    remainingSeconds = intervalSeconds
                }
            }
        }
    }

    // Cálculo exibido conforme o modo escolhido pelo usuário
    val displayedUsedMb = if (showRawCacheAsUsed) ramData.usedWithCacheMb else ramData.usedActiveMb
    val displayedFreeMb = ramData.totalRealMb - displayedUsedMb
    val usagePercent = if (ramData.totalRealMb > 0) {
        ((displayedUsedMb.toFloat() / ramData.totalRealMb.toFloat()) * 100f).coerceIn(1f, 99f)
    } else 0f

    val gaugeColor = when {
        usagePercent >= 80f -> Color(0xFFFF5252)
        usagePercent >= 65f -> Color(0xFFFFAB00)
        else -> Color(0xFF00E676)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cabeçalho
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RAM BOOSTER PRO",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Hardware Detectado: ${ramData.commercialGb} GB (${ramData.totalRealMb} MB reais)",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
                Surface(
                    color = if (autoBoosterEnabled) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF1E293B),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (autoBoosterEnabled) "AUTO: ${remainingSeconds}s" else "MANUAL",
                        color = if (autoBoosterEnabled) Color(0xFF00E676) else Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Painel Principal de Medição Real
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { usagePercent / 100f },
                            modifier = Modifier.size(150.dp),
                            strokeWidth = 14.dp,
                            color = gaugeColor,
                            trackColor = Color(0xFF0B0F19)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${usagePercent.roundToInt()}%",
                                color = Color.White,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "$displayedUsedMb / ${ramData.totalRealMb} MB",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Detalhamento completo do /proc/meminfo
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        RamMetricBox("Usada Ativa", "${ramData.usedActiveMb} MB", Color(0xFF38BDF8))
                        RamMetricBox("Em Cache", "${ramData.cachedAndBuffersMb} MB", Color(0xFFFBBF24))
                        RamMetricBox("Livre Real", "$displayedFreeMb MB", Color(0xFF00E676))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Alternador de Modo de Leitura (Sistema vs Outros Apps)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0B0F19))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (showRawCacheAsUsed) "Modo: Somar Cache como Usado" else "Modo: Padrão do Sistema Android",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (showRawCacheAsUsed) "Mostra valor igual a outros apps de limpeza" else "Mostra valor igual às Configurações do celular",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = showRawCacheAsUsed,
                            onCheckedChange = { showRawCacheAsUsed = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = statusText,
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                isOptimizing = true
                                statusText = "Limpando processos em segundo plano..."
                                val beforeAvail = readRealHardwareRam(context).availRealMb
                                val killed = executeRealRamClean(context)
                                delay(700L)
                                ramData = readRealHardwareRam(context)
                                val freed = (ramData.availRealMb - beforeAvail).coerceAtLeast(0L)
                                totalFreedAccumulatedMb += freed
                                statusText = if (freed > 0) {
                                    "Otimizado! +$freed MB liberados ($killed apps higienizados)."
                                } else {
                                    "RAM já está no nível mais leve! ($killed processos verificados)."
                                }
                                isOptimizing = false
                            }
                        },
                        enabled = !isOptimizing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            text = if (isOptimizing) "OTIMIZANDO MEMÓRIA..." else "DEIXAR MAIS RÁPIDO AGORA",
                            color = Color(0xFF052E16),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        // Card do Auto RAM Booster com Temporizador em Segundos
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto RAM Booster (Em Segundos)",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Limpa a memória automaticamente no intervalo escolhido",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = autoBoosterEnabled,
                            onCheckedChange = { autoBoosterEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Botões Rápidos de Segundos
                    val presets = listOf(5, 10, 15, 30, 60)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { sec ->
                            val selected = intervalSeconds == sec
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) Color(0xFF00E676) else Color(0xFF0B0F19))
                                    .clickable {
                                        intervalSeconds = sec
                                        remainingSeconds = sec
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${sec}s",
                                    color = if (selected) Color(0xFF052E16) else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Ajuste fino de segundos (-5s / +5s)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                intervalSeconds = (intervalSeconds - 5).coerceAtLeast(3)
                                remainingSeconds = intervalSeconds
                            }
                        ) {
                            Text("- 5s", color = Color.White)
                        }

                        Text(
                            text = "Intervalo: $intervalSeconds segundos",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        OutlinedButton(
                            onClick = {
                                intervalSeconds = (intervalSeconds + 5).coerceAtMost(600)
                                remainingSeconds = intervalSeconds
                            }
                        ) {
                            Text("+ 5s", color = Color.White)
                        }
                    }

                    if (autoBoosterEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { remainingSeconds.toFloat() / intervalSeconds.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF00E676),
                            trackColor = Color(0xFF0B0F19)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Próxima limpeza em ${remainingSeconds}s",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ciclos: $autoCyclesCount | Total: +$totalFreedAccumulatedMb MB",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // Lista de Aplicativos Instalados para Forçar Parada Real
        item {
            Text(
                text = "Apps Instalados (${userApps.size}) - Toque para Forçar Parada",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(userApps) { app ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.packageName,
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { openAppSettingsToForceStop(context, app.packageName) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Forçar Parada", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun RamMetricBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Text(text = value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

// Lê os dados reais do Kernel Linux (/proc/meminfo) + ActivityManager
fun readRealHardwareRam(context: Context): RealHardwareRam {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    am.getMemoryInfo(memInfo)

    var memTotalKb = 0L
    var memFreeKb = 0L
    var buffersKb = 0L
    var cachedKb = 0L

    try {
        val reader = RandomAccessFile("/proc/meminfo", "r")
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val parts = line!!.trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val value = parts[1].toLongOrNull() ?: 0L
                when (parts[0]) {
                    "MemTotal:" -> memTotalKb = value
                    "MemFree:" -> memFreeKb = value
                    "Buffers:" -> buffersKb = value
                    "Cached:" -> cachedKb = value
                }
            }
        }
        reader.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }

    val totalRealMb = memInfo.totalMem / (1024 * 1024)
    val availRealMb = memInfo.availMem / (1024 * 1024)
    val usedActiveMb = totalRealMb - availRealMb

    val freeRawMb = if (memFreeKb > 0) memFreeKb / 1024 else availRealMb / 2
    val cachedMb = if (cachedKb + buffersKb > 0) (cachedKb + buffersKb) / 1024 else (availRealMb - freeRawMb).coerceAtLeast(0L)
    val usedWithCacheMb = (totalRealMb - freeRawMb).coerceIn(usedActiveMb, totalRealMb)

    val commercialGb = when {
        totalRealMb > 12500 -> 16
        totalRealMb > 8500 -> 12
        totalRealMb > 6200 -> 8
        totalRealMb > 4200 -> 6
        totalRealMb > 2200 -> 4
        else -> 2
    }

    return RealHardwareRam(
        commercialGb = commercialGb,
        totalRealMb = totalRealMb,
        usedActiveMb = usedActiveMb,
        availRealMb = availRealMb,
        usedWithCacheMb = usedWithCacheMb,
        cachedAndBuffersMb = cachedMb,
        freeRawMb = freeRawMb
    )
}

// Encerra processos em segundo plano de verdade
suspend fun executeRealRamClean(context: Context): Int = withContext(Dispatchers.IO) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val pm = context.packageManager
    val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    var count = 0

    for (app in packages) {
        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (!isSystem && app.packageName != context.packageName) {
            try {
                am.killBackgroundProcesses(app.packageName)
                count++
            } catch (_: Exception) {
            }
        }
    }
    System.gc()
    return@withContext count
}

// Lista os apps instalados pelo usuário
suspend fun loadUserInstalledApps(context: Context): List<AppProcessItem> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    val result = mutableListOf<AppProcessItem>()

    for (app in apps) {
        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (!isSystem && app.packageName != context.packageName) {
            val label = pm.getApplicationLabel(app).toString()
            result.add(AppProcessItem(label, app.packageName))
        }
    }
    return@withContext result.sortedBy { it.appName.lowercase() }
}

// Abre a tela do Android para o usuário clicar em "Forçar Parada" no app pesado
fun openAppSettingsToForceStop(context: Context, packageName: String) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
