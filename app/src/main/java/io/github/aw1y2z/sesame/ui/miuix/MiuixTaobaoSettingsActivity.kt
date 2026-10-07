package io.github.aw1y2z.sesame.ui.miuix

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aw1y2z.sesame.data.ConfigV2
import io.github.aw1y2z.sesame.data.Model
import io.github.aw1y2z.sesame.data.modelFieldExt.IntegerModelField
import io.github.aw1y2z.sesame.model.task.taobaoFarm.TaobaoFarm
import io.github.aw1y2z.sesame.util.Log
import io.github.aw1y2z.sesame.util.ToastUtil
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MiuixTaobaoSettingsActivity : MiuixBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Model.initAllModel()
        setAppContent {
            TaobaoSettingsContent(this)
        }
    }

    override fun onBackPressed() {
        save()
        super.onBackPressed()
    }

    fun saveAndFinish() {
        save()
        finish()
    }

    fun save() {
        if (!ConfigV2.hasFieldChanges()) return
        if (ConfigV2.save(null, true)) {
            ToastUtil.show(this, "保存成功！")
            sendRestartIfNeeded()
        }
    }

    private fun sendRestartIfNeeded() {
        try {
            sendBroadcast(Intent("com.taobao.taobao.sesame.restart"))
        } catch (th: Throwable) {
            Log.printStackTrace(th)
        }
    }

    fun sendTaobaoExecute(task: String) {
        try {
            // Greezer 拦截跨进程广播，改用信号文件触发
            // 淘宝进程在 onResume 时检查并消费此文件
            val signalFile = java.io.File("/sdcard/sesame_tb_signal")
            signalFile.writeText(task)
            ToastUtil.show(this, "已发送，切到淘宝触发执行")
        } catch (th: Throwable) {
            Log.printStackTrace(th)
            ToastUtil.show(this, "发送失败: ${th.message}")
        }
    }

    fun checkTaobaoStatus() {
        try {
            val signalFile = java.io.File("/sdcard/sesame_tb_signal")
            signalFile.writeText("status")
            ToastUtil.show(this, "已发送，切到淘宝检查状态")
        } catch (th: Throwable) {
            Log.printStackTrace(th)
        }
    }
}

@Composable
fun TaobaoSettingsContent(activity: MiuixTaobaoSettingsActivity) {
    val model = Model.getModel(TaobaoFarm::class.java)
    val modelConfig = Model.getModelConfigMap()["TaobaoFarm"]

    if (model == null || modelConfig == null) {
        top.yukonga.miuix.kmp.basic.Text(
            "淘宝农场模块未初始化",
            color = MiuixTheme.colorScheme.error
        )
        return
    }

    Scaffold(
        topBar = {
            LogTopBar(
                title = "淘宝农场设置",
                onBack = { activity.saveAndFinish() },
                onImport = null,
                onExport = null,
                onClear = null
            )
        },
        containerColor = MiuixTheme.colorScheme.surface
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SmallTitle(text = "淘宝农场")
            CardColumn {
                val enableField = model.getEnableField()
                var enabled by remember { mutableStateOf(enableField.value as? Boolean ?: false) }
                SwitchPreference(
                    title = "开启淘宝农场",
                    summary = "启用后自动完成淘宝农场任务",
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        enableField.setObjectValue(it)
                    }
                )
            }
            Spacer(Modifier.height(12.dp))

            SmallTitle(text = "任务配置")
            CardColumn {
                val waterCountField = modelConfig.getModelFieldExt<IntegerModelField>("tbFarmWaterCount")
                if (waterCountField != null) {
                    var current by remember { mutableStateOf(waterCountField.value as? Int ?: 10) }
                    var expanded by remember { mutableStateOf(false) }
                    ArrowPreference(
                        title = "每日浇水次数",
                        summary = "$current（0~50）",
                        onClick = { expanded = !expanded }
                    )
                    if (expanded) {
                        var text by remember { mutableStateOf(current.toString()) }
                        top.yukonga.miuix.kmp.basic.TextField(
                            value = text,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() }
                                text = filtered
                                filtered.toIntOrNull()?.let { v ->
                                    if (v in 0..50) {
                                        waterCountField.setConfigValue(v.toString())
                                        current = v
                                    }
                                }
                            },
                            label = "",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                val browseSwipesField = modelConfig.getModelFieldExt<IntegerModelField>("tbFarmBrowseSwipes")
                if (browseSwipesField != null) {
                    var current by remember { mutableStateOf(browseSwipesField.value as? Int ?: 3) }
                    var expanded by remember { mutableStateOf(false) }
                    ArrowPreference(
                        title = "每次浏览滑动次数",
                        summary = "$current（1~10）",
                        onClick = { expanded = !expanded }
                    )
                    if (expanded) {
                        var text by remember { mutableStateOf(current.toString()) }
                        top.yukonga.miuix.kmp.basic.TextField(
                            value = text,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() }
                                text = filtered
                                filtered.toIntOrNull()?.let { v ->
                                    if (v in 1..10) {
                                        browseSwipesField.setConfigValue(v.toString())
                                        current = v
                                    }
                                }
                            },
                            label = "",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            SmallTitle(text = "状态")
            CardColumn {
                ArrowPreference(
                    title = "检查淘宝 hook 状态",
                    summary = "确认模块已注入淘宝进程",
                    onClick = { activity.checkTaobaoStatus() }
                )
            }
            Spacer(Modifier.height(12.dp))

            SmallTitle(text = "立即执行")
            CardColumn {
                ArrowPreference(
                    title = "立即浇水",
                    summary = "发送到淘宝进程执行",
                    onClick = { activity.sendTaobaoExecute("water") }
                )
                ArrowPreference(
                    title = "立即做全部任务",
                    summary = "浇水 + 开箱子 + 领礼包 + 做任务",
                    onClick = { activity.sendTaobaoExecute("all") }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
