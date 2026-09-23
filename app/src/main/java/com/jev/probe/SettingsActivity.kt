package com.jev.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ConfigBackup
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.ModelCatalog
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.Route
import com.jev.probe.jev.VisionClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent get() = Palette.accent(this@SettingsActivity)
    private val ink get() = Palette.ink(this@SettingsActivity)
    private val sub get() = Palette.sub(this@SettingsActivity)
    private val pillOff get() = Palette.pillOff(this@SettingsActivity)

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProviderIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        window.decorView.setBackgroundColor(Palette.bg(this@SettingsActivity))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 接口 ===================
        root.addView(section("接口"))

        // --- 判断接口（Jev） ---
        val judgeCard = card()
        judgeCard.addView(cardTitle("判断接口（Jev）"))
        judgeCard.addView(text("读对方消息、给意图判断和候选排序。必须配置。", 12f, sub))

        judgeBaseEdit = edit(prefs.judgeBaseUrl, Prefs.DEFAULT_JUDGE_BASE_KNOX)
        judgeModelEdit = edit(prefs.judgeModel, Prefs.DEFAULT_JUDGE_MODEL_KNOX)
        judgeProviderIdx = when (prefs.judgeProvider) {
            Prefs.PROVIDER_TYPESAFE -> 1
            Prefs.PROVIDER_OPENROUTER -> 2
            Prefs.PROVIDER_CUSTOM -> 3
            else -> 0
        }
        judgeCard.addView(pills(
            listOf("Knox Studio", "TypeSafe 直连", "OpenRouter", "自定义"), judgeProviderIdx) { idx ->
            judgeProviderIdx = idx
            when (idx) {
                0 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_KNOX)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_KNOX)
                }
                1 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_TYPESAFE)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE)
                }
                2 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
                }
                // Custom POSTs the box verbatim, so a preset HOST left in the box
                // would hit the API root. Expand it into the full endpoint the
                // preset would have used; anything hand-typed is left alone.
                3 -> judgeBaseEdit.setText(expandJudgeUrl(judgeBaseEdit.text.toString()))
            }
        })
        judgeCard.addView(label("Base URL"))
        judgeCard.addView(judgeBaseEdit)
        judgeCard.addView(text("Knox Studio 拼 /systemone；TypeSafe 拼 /v1/systemone；" +
            "OpenRouter 拼 /alpha/decisions；自定义按原样 POST。", 11f, sub))
        judgeCard.addView(label("密钥"))
        judgeCard.addView(edit(prefs.judgeKey, "sk-...", password = true).also { judgeKeyEdit = it })
        judgeCard.addView(label("模型"))
        judgeCard.addView(judgeModelEdit)
        val judgeResult = resultText()
        // The judge route is a Jev/System One endpoint, not a chat-completions
        // one, so it only offers a model list on the custom path where the user
        // points at their own OpenAI-compatible gateway.
        judgeCard.addView(rowBtn("获取模型列表（仅自定义档）") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            if (base.isBlank()) { judgeResult.text = "先填 Base URL"; return@rowBtn }
            judgeResult.text = "正在获取模型列表…"
            worker.execute {
                var err: String? = null
                val models = try {
                    ModelCatalog.fetch(base, key, Route.JUDGE)
                } catch (e: Exception) { err = e.message; emptyList() }
                main.post {
                    if (err != null) { judgeResult.text = "获取失败：$err"; return@post }
                    if (models.isEmpty()) { judgeResult.text = "该地址没有返回任何模型"; return@post }
                    judgeResult.text = "共 ${models.size} 个模型，选一个："
                    showModelPicker(models, judgeModelEdit, judgeResult)
                }
            }
        })
        judgeCard.addView(cardBtn("测试判断") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            val model = judgeModelEdit.text.toString().trim()
            if (key.isBlank()) { judgeResult.text = "请先填密钥"; return@cardBtn }
            judgeResult.text = "测试中…"
            // Provider follows the address when it is still a known preset host,
            // so a stale pill selection cannot send a TypeSafe path to OpenRouter.
            val provider = resolveJudgeProvider(judgeProviderIdx, base)
            if (provider == Prefs.PROVIDER_CUSTOM && base.isBlank()) {
                judgeResult.text = "自定义档要填完整 URL（带路径）"; return@cardBtn
            }
            // Custom means we know nothing about the endpoint — guessing a model
            // name here would test something the user never asked for.
            if (provider == Prefs.PROVIDER_CUSTOM && model.isBlank()) {
                judgeResult.text = "请填写模型名"; return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_JUDGE) {
                judgeProvider = provider
                judgeBaseUrl = base.ifBlank { defaultJudgeBase(provider) }
                judgeKey = key
                judgeModel = model.ifBlank { defaultJudgeModel(provider) }
            }
            worker.execute {
                val t0 = System.currentTimeMillis()
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在")))
                val a = JudgeClient(probe).judge(demo, prefs.relationship)
                val ms = System.currentTimeMillis() - t0
                main.post {
                    judgeResult.text = if (a.error != null) "失败（${ms}ms）：${a.error}"
                    else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}" +
                        "（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        judgeCard.addView(judgeResult)
        root.addView(judgeCard)

        // --- 回复接口 ---
        val replyCard = card()
        replyCard.addView(cardTitle("回复接口"))
        replyCard.addView(text("生成 3 条候选回复。任何 OpenAI 兼容地址，填到 /v1 为止。" +
            "注意：判断接口与回复接口是两家服务，密钥各不相同，都要填。", 12f, sub))

        replyBaseEdit = edit(prefs.replyBaseUrl, Prefs.TOKENRHYTHM_BASE)
        replyModelEdit = edit(prefs.replyModel, Prefs.TOKENRHYTHM_MODEL)
        val replyIdx = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.TOKENRHYTHM_BASE -> 0
            Prefs.DEFAULT_REPLY_BASE -> 1
            Prefs.DEEPSEEK_BASE -> 2
            Prefs.DASHSCOPE_BASE -> 3
            else -> 4
        }
        replyCard.addView(pills(
            listOf("TokenRhythm", "OpenRouter", "DeepSeek 官方", "通义兼容", "自定义"), replyIdx) { idx ->
            when (idx) {
                0 -> { replyBaseEdit.setText(Prefs.TOKENRHYTHM_BASE); replyModelEdit.setText(Prefs.TOKENRHYTHM_MODEL) }
                1 -> { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE); replyModelEdit.setText(Prefs.DEFAULT_REPLY_MODEL) }
                2 -> { replyBaseEdit.setText(Prefs.DEEPSEEK_BASE); replyModelEdit.setText(Prefs.DEEPSEEK_MODEL) }
                3 -> { replyBaseEdit.setText(Prefs.DASHSCOPE_BASE); replyModelEdit.setText(Prefs.DASHSCOPE_MODEL) }
            }
        })
        replyCard.addView(label("Base URL"))
        replyCard.addView(replyBaseEdit)
        replyCard.addView(label("密钥"))
        replyCard.addView(edit(prefs.replyKey, "TokenRhythm 的密钥（留空才回落到判断接口密钥）", password = true).also { replyKeyEdit = it })
        replyCard.addView(label("模型"))
        replyCard.addView(replyModelEdit)
        // Declared before the buttons that write into it (both the model picker
        // and the connectivity test report through this one line).
        val replyResult = resultText()
        thinkRow = toggleRow("让模型先思考再回答（更准但更慢）", prefs.replyThinking)
        replyCard.addView(thinkRow)
        replyCard.addView(text("实测同一提示词：关掉思考中位 4.5 秒，开着 7.3 秒。聊天回复一般不需要思考。",
            11f, sub))
        replyCard.addView(rowBtn("获取模型列表") {
            val base = replyBaseEdit.text.toString().trim().ifBlank { Prefs.TOKENRHYTHM_BASE }
            val key = replyKeyEdit.text.toString().trim().ifBlank { judgeKeyEdit.text.toString().trim() }
            replyResult.text = "正在获取模型列表…"
            worker.execute {
                var err: String? = null
                val models = try {
                    ModelCatalog.fetch(base, key, Route.REPLY)
                } catch (e: Exception) { err = e.message; emptyList() }
                main.post {
                    if (err != null) { replyResult.text = "获取失败：$err"; return@post }
                    if (models.isEmpty()) { replyResult.text = "该地址没有返回任何模型"; return@post }
                    replyResult.text = "共 ${models.size} 个模型，选一个："
                    showModelPicker(models, replyModelEdit, replyResult)
                }
            }
        })
        replyCard.addView(cardBtn("测试回复") {
            val base = replyBaseEdit.text.toString().trim()
            val model = replyModelEdit.text.toString().trim()
            val probe = draftPrefs(SCRATCH_REPLY) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = base.ifBlank { Prefs.TOKENRHYTHM_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                replyModel = model.ifBlank { Prefs.TOKENRHYTHM_MODEL }
            }
            if (probe.effectiveReplyKey().isBlank()) { replyResult.text = "请先填密钥（或填判断接口密钥）"; return@cardBtn }
            replyResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    ReplyClient(probe).ping()
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    replyResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        // --- 视觉接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("视觉接口（OCR 用，可不填）"))
        visionCard.addView(text("读不到控件树的 App 走截图识别。TokenRhythm 的 mimo-v2.6-flash 支持图片，留空即走它。", 12f, sub))

        visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.TOKENRHYTHM_BASE)
        visionModelEdit = edit(prefs.visionModel, Prefs.TOKENRHYTHM_MODEL)
        val visionIdx = when (prefs.visionBaseUrl.trim().trimEnd('/')) {
            Prefs.TOKENRHYTHM_BASE -> 0
            Prefs.DEFAULT_VISION_BASE -> 1
            Prefs.DASHSCOPE_BASE -> 2
            else -> 3
        }
        visionCard.addView(pills(
            listOf("TokenRhythm", "OpenRouter", "通义兼容", "自定义"), visionIdx) { idx ->
            when (idx) {
                0 -> { visionBaseEdit.setText(Prefs.TOKENRHYTHM_BASE); visionModelEdit.setText(Prefs.TOKENRHYTHM_MODEL) }
                1 -> { visionBaseEdit.setText(Prefs.DEFAULT_VISION_BASE); visionModelEdit.setText(Prefs.DEFAULT_VISION_MODEL) }
                2 -> { visionBaseEdit.setText(Prefs.DASHSCOPE_BASE); visionModelEdit.setText(Prefs.DASHSCOPE_VISION_MODEL) }
            }
        })
        visionCard.addView(label("Base URL"))
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("密钥"))
        visionCard.addView(edit(prefs.visionKey, "留空则用回复接口密钥", password = true).also { visionKeyEdit = it })
        visionCard.addView(label("模型"))
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(rowBtn("获取模型列表") {
            val base = visionBaseEdit.text.toString().trim().ifBlank { Prefs.TOKENRHYTHM_BASE }
            val key = visionKeyEdit.text.toString().trim()
                .ifBlank { replyKeyEdit.text.toString().trim() }
                .ifBlank { judgeKeyEdit.text.toString().trim() }
            visionResult.text = "正在获取模型列表…"
            worker.execute {
                var err: String? = null
                val models = try {
                    ModelCatalog.fetch(base, key, Route.VISION)
                } catch (e: Exception) { err = e.message; emptyList() }
                main.post {
                    if (err != null) { visionResult.text = "获取失败：$err"; return@post }
                    if (models.isEmpty()) { visionResult.text = "该地址没有返回任何模型"; return@post }
                    visionResult.text = "共 ${models.size} 个模型，选一个："
                    showModelPicker(models, visionModelEdit, visionResult)
                }
            }
        })
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            if (!VisionClient.supportsVision(visionBase.ifBlank { Prefs.TOKENRHYTHM_BASE })) {
                visionResult.text = GUARD_NO_VISION
                return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_VISION) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.TOKENRHYTHM_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.TOKENRHYTHM_MODEL }
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥（或填回复/判断接口密钥）"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        visionCard.addView(visionResult)
        root.addView(visionCard)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给 Jev 判断用）"))
        relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)

        // --- 群聊 ---
        groupRow = toggleRow("群聊模式", prefs.groupMode)
        card2.addView(groupRow)
        card2.addView(text("开启后：保留发言人昵称、按「群里的人」而不是「对方」来判断，" +
            "候选回复也按群聊礼节生成（更短、不煽情、不乱用亲密称呼）。",
            11f, sub))
        card2.addView(label("只回复谁（群聊，留空=最后发言的人）"))
        groupTargetEdit = edit(prefs.groupTarget, "填群昵称，如：老王")
        card2.addView(groupTargetEdit)
        card2.addView(text("填了之后，判断和候选回复都针对这个人；面板里他/她的发言会标 ▸。",
            11f, sub))

        // --- 上下文窗口 ---
        // These were hard-coded (10 for the model, 6 for the panel), which is why
        // replies looked like they only answered the last line and the panel
        // could never show more than six messages.
        card2.addView(label("生成回复时看多少条消息（${Prefs.MIN_WINDOW}–${Prefs.MAX_WINDOW}）"))
        replyWindowEdit = edit(prefs.replyWindow.toString(), Prefs.DEFAULT_WINDOW.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(replyWindowEdit)
        card2.addView(text("给回复模型的整段对话条数。越大越能接住上下文，但更慢、更贵。默认 ${Prefs.DEFAULT_WINDOW}。",
            11f, sub))
        card2.addView(label("判断对方意图时看多少条消息（${Prefs.MIN_WINDOW}–${Prefs.MAX_WINDOW}）"))
        judgeWindowEdit = edit(prefs.judgeWindow.toString(), Prefs.DEFAULT_JUDGE_WINDOW.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(judgeWindowEdit)
        card2.addView(text("判断比回复需要更多上下文（意图取决于话题怎么走到这里），默认 ${Prefs.DEFAULT_JUDGE_WINDOW}。",
            11f, sub))

        // --- 附加提示词 ---
        // The built-in prompt is always sent and is designed to work alone; this
        // only refines it, and is appended AFTER the built-in text so a single
        // line here cannot override the persona.
        card2.addView(label("附加提示词（可留空）"))
        userPromptEdit = edit(prefs.userPrompt, "留空即可；只填想额外补充的要求").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        card2.addView(userPromptEdit)
        card2.addView(text("内置提示词已经默认生效（让人味、短、只说人话）。这里写的内容会拼在内置提示词后面。" +
            "比如「叫我老王」「别用哈哈」。", 11f, sub))

        // --- OCR 兜底（B 阶段）---
        ocrFallbackRow = toggleRow("树读不到正文时用 OCR 兜底", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text("飞书正文是画上去的、微信伪装失效时也读不到，这时截一次屏本地识别（不上传）。", 11f, sub))
        ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时 OCR 认完只亮悬浮球，点一下再分析。", 11f, sub))

        // --- 知识库 / 关联上下文（D 阶段） ---
        ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("关闭时不写任何聊天内容到磁盘；笔记与联系人匹配仍然照常工作。", 11f, sub))
        card2.addView(label("注入最近历史条数（0–100）"))
        ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空知识库与历史") {
            val c = KbStore.get(this).counts()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库与历史")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史。" +
                    "密钥、白名单等设置不受影响。不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    kbResult.text = "已清空知识库与历史"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        // Was an unstyled TextView: no background, no border, same colour as the
        // card, so on screen it read as a bare label ("全白的，只能看到字") with no
        // hint it was tappable, and its result appeared far below it. It is now a
        // real outlined button with the result directly underneath.
        card2.addView(cardBtn("运行自检") {
            kbResult.text = "自检中…"
            worker.execute {
                val out = try { KbSelfCheck.run(this@SettingsActivity) }
                catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                main.post { kbResult.text = out }
            }
        })
        card2.addView(text("检查联系人与笔记匹配、历史去重、上下文注入是否正常。会临时建一条测试数据并自动删除。",
            11f, sub))
        card2.addView(kbResult)
        root.addView(card2)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 关于与隐私 ===================
        root.addView(section("关于与隐私"))
        val aboutCard = card()
        aboutCard.addView(text(
            "这个 App 会读取你当前聊天窗口的文字，发给你自己配置的模型接口做判断和起草回复。作者不运营服务器，收不到你的数据。",
            12f, sub))
        aboutCard.addView(cardBtn("开源仓库") { openUrl(REPO_URL) })
        aboutCard.addView(text(
            "基于 Jev 聊天助手（github.com/jev-chat/jev-chat-jarvis）二次开发，MIT 协议。" +
                "本项目与原作者无隶属关系，不代表其出品或背书。",
            11f, sub).apply { setPadding(0, dp(10), 0, 0) })
        aboutCard.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(10), 0, dp(2)) })
        root.addView(aboutCard)

        // =================== 备份与恢复 ===================
        // Placed above 保存 so it is independent of it: export writes what is
        // currently saved, import overwrites it. Sideloading means every update
        // is an uninstall + install, which wipes app storage — this is the way
        // back, and it is the reason the section exists at all.
        root.addView(section("备份与恢复"))
        val backupCard = card()
        backupCard.addView(text(
            "装新版要卸载重装，App 私有存储会被清空（密钥、设置、知识库都丢）。" +
                "导出一个配置文件放到手机里，重装后导回来即可。",
            12f, sub))
        val backupResult = resultText()
        backupResultView = backupResult
        // Export applies the form FIRST (see exportConfig). The bug this fixes:
        // the user types both API keys, scrolls down to this card (which sits
        // ABOVE the 保存 button), taps 导出 — and the file captured whatever was
        // last *saved*, i.e. no keys at all. Importing that file then restores
        // nothing, which is exactly what was reported.
        backupCard.addView(cardBtn("导出设置到文件（含当前输入框内容）") { exportConfig(backupResult) })
        backupCard.addView(cardBtn("从文件恢复设置") { importConfig(backupResult) })
        backupCard.addView(text(
            "注意：导出的文件里包含你的 API 密钥（明文），因为它就是用来免去重填的。" +
                "请放在自己手机的私有目录，不要发到群里或上传网盘。",
            11f, sub).apply { setPadding(0, dp(8), 0, 0) })
        backupCard.addView(backupResult)
        root.addView(backupCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            applyFormToPrefs()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    /**
     * Write every field on this screen into [Prefs].
     *
     * Shared by the 保存 button and by export: export used to read only what was
     * already saved, so typing a key and tapping 导出 before 保存 produced a
     * backup with no key in it — the reported "导入后什么都没有" bug. Both paths
     * now go through this one function, so they cannot drift apart.
     *
     * The field references are captured in locals by the caller (they are built
     * in onCreate); this holds them as properties instead, since export can run
     * from onActivityResult outside that scope.
     */
    private fun applyFormToPrefs() {
        // Address wins over the pill: a preset HOST in the box means that
        // preset's provider (and so its path), whatever the pill last said.
        val judgeBaseTyped = judgeBaseEdit.text.toString().trim()
        val judgeProv = resolveJudgeProvider(judgeProviderIdx, judgeBaseTyped)
        val judgeModelTyped = judgeModelEdit.text.toString().trim()
        prefs.judgeProvider = judgeProv
        // Blank falls back to THIS provider's preset — never OpenRouter's by
        // default. Custom is left exactly as typed (blank included): guessing
        // a URL for it would silently point somewhere the user did not choose.
        prefs.judgeBaseUrl = when {
            judgeBaseTyped.isNotBlank() -> judgeBaseTyped
            judgeProv == Prefs.PROVIDER_CUSTOM -> ""
            else -> defaultJudgeBase(judgeProv)
        }
        prefs.judgeKey = judgeKeyEdit.text.toString()
        prefs.judgeModel = when {
            judgeModelTyped.isNotBlank() -> judgeModelTyped
            judgeProv == Prefs.PROVIDER_CUSTOM -> ""
            else -> defaultJudgeModel(judgeProv)
        }

        prefs.replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.TOKENRHYTHM_BASE }
        prefs.replyKey = replyKeyEdit.text.toString()
        prefs.replyModel = replyModelEdit.text.toString().trim().ifBlank { Prefs.TOKENRHYTHM_MODEL }
        prefs.replyThinking = (thinkRow?.tag as? Boolean) ?: false

        prefs.visionBaseUrl = visionBaseEdit.text.toString().trim()
        prefs.visionKey = visionKeyEdit.text.toString()
        prefs.visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.TOKENRHYTHM_MODEL }

        prefs.relationship = relEdit?.text?.toString() ?: prefs.relationship
        prefs.whitelist = wlEdit?.text?.toString()?.split("\n")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: prefs.whitelist
        prefs.autoAnalyze = (autoRow?.tag as? Boolean) ?: true
        prefs.ocrFallback = (ocrFallbackRow?.tag as? Boolean) ?: true
        prefs.ocrAutoAnalyze = (ocrAutoRow?.tag as? Boolean) ?: false
        prefs.contextEnabled = (ctxRow?.tag as? Boolean) ?: false
        prefs.contextHistoryCount =
            ctxCountEdit?.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: 30
        prefs.overlayOpacity = (seek?.progress ?: 32) + 60
        // Chat windows (how much history the analysis sees).
        prefs.replyWindow = replyWindowEdit?.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(Prefs.MIN_WINDOW, Prefs.MAX_WINDOW) ?: prefs.replyWindow
        prefs.judgeWindow = judgeWindowEdit?.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(Prefs.MIN_WINDOW, Prefs.MAX_WINDOW) ?: prefs.judgeWindow
        prefs.userPrompt = userPromptEdit?.text?.toString() ?: prefs.userPrompt
        prefs.groupMode = (groupRow?.tag as? Boolean) ?: true
        prefs.groupTarget = groupTargetEdit?.text?.toString()?.trim() ?: ""
    }

    // Fields the analyse/export paths need after onCreate has finished.
    private lateinit var judgeBaseEdit: EditText
    private lateinit var judgeModelEdit: EditText
    private lateinit var replyBaseEdit: EditText
    private lateinit var replyModelEdit: EditText
    private lateinit var visionBaseEdit: EditText
    private lateinit var visionModelEdit: EditText
    private var relEdit: EditText? = null
    private var wlEdit: EditText? = null
    private var thinkRow: LinearLayout? = null
    private var autoRow: LinearLayout? = null
    private var ocrFallbackRow: LinearLayout? = null
    private var ocrAutoRow: LinearLayout? = null
    private var ctxRow: LinearLayout? = null
    private var ctxCountEdit: EditText? = null
    private var seek: SeekBar? = null
    private var replyWindowEdit: EditText? = null
    private var judgeWindowEdit: EditText? = null
    private var userPromptEdit: EditText? = null
    private var groupRow: LinearLayout? = null
    private var groupTargetEdit: EditText? = null

    // Held as fields because several test buttons read each other's key box.
    private lateinit var judgeKeyEdit: EditText
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    /** The backup card's status line; onActivityResult writes its outcome here. */
    private var backupResultView: TextView? = null

    private fun providerOf(idx: Int) = when (idx) {
        1 -> Prefs.PROVIDER_TYPESAFE
        2 -> Prefs.PROVIDER_OPENROUTER
        3 -> Prefs.PROVIDER_CUSTOM
        else -> Prefs.PROVIDER_KNOX
    }

    /**
     * The provider actually implied by what is in the address box. A preset host
     * carries its own path (`/alpha/decisions`, `/systemone`), so leaving that
     * host in the box while the pill says something else would POST the wrong
     * path — or, for custom, the bare API root.
     */
    private fun resolveJudgeProvider(idx: Int, base: String): String =
        when (base.trim().trimEnd('/')) {
            Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.PROVIDER_OPENROUTER
            Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.PROVIDER_TYPESAFE
            Prefs.DEFAULT_JUDGE_BASE_KNOX -> Prefs.PROVIDER_KNOX
            else -> providerOf(idx)
        }

    /** The full endpoint a preset host would have been expanded to. */
    private fun expandJudgeUrl(base: String): String = when (base.trim().trimEnd('/')) {
        Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions"
        Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_KNOX -> Prefs.DEFAULT_JUDGE_BASE_KNOX + "/systemone"
        else -> base.trim()
    }

    private fun defaultJudgeBase(provider: String): String = when (provider) {
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE
        Prefs.PROVIDER_OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER
        else -> Prefs.DEFAULT_JUDGE_BASE_KNOX
    }

    private fun defaultJudgeModel(provider: String): String = when (provider) {
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE
        Prefs.PROVIDER_OPENROUTER -> Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER
        else -> Prefs.DEFAULT_JUDGE_MODEL_KNOX
    }

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. Each
     * button gets its OWN scratch file — they used to share one and clear it out
     * from under each other when two tests overlapped. The real config is never
     * touched either way.
     */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        getSharedPreferences(scratchName, MODE_PRIVATE).edit().clear().commit()
        return Prefs(this, scratchName).apply(fill)
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    // ------------------------------------------------------- backup / restore

    /**
     * Write the config to a user-chosen file via SAF.
     *
     * SAF (ACTION_CREATE_DOCUMENT) rather than a fixed path: the target must be
     * outside app-private storage to survive an uninstall, and writing to
     * shared storage directly would need a storage permission on every Android
     * version. The user picks the location (Downloads / Documents / an SD card),
     * which is also where they can find it again after reinstalling.
     */
    private fun exportConfig(out: TextView) {
        out.text = ""
        // Apply what is on screen BEFORE reading prefs. Without this, a key typed
        // but not yet saved is missing from the file and the backup turns out
        // empty after a reinstall — the reported "导入后什么都没有" bug. Export
        // means "save what I see, then write it out".
        applyFormToPrefs()
        val name = ConfigBackup.suggestedName()
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TITLE, name)
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT) }
            .onFailure { out.text = "打不开文件选择器：${it.javaClass.simpleName}" }
    }

    private fun importConfig(out: TextView) {
        out.text = ""
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT) }
            .onFailure { out.text = "打不开文件选择器：${it.javaClass.simpleName}" }
    }

    @Deprecated("startActivityForResult is fine for two one-shot pickers on minSdk 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val label = backupResultView ?: return
        when (requestCode) {
            REQ_EXPORT -> {
                label.text = "已写入，正在导出…"
                runCatching {
                    val json = ConfigBackup.exportJson(prefs)
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入所选文件")
                    // Report what really went in, so an empty export is visible
                    // immediately instead of being discovered after a reinstall.
                    val nSettings = ConfigBackup.countSettings(json)
                    val hasJudge = prefs.judgeKey.isNotBlank()
                    val hasReply = prefs.replyKey.isNotBlank()
                    val keyState = when {
                        hasJudge && hasReply -> "含两个密钥"
                        hasJudge -> "只含判断密钥（回复密钥是空的）"
                        hasReply -> "只含回复密钥（判断密钥是空的）"
                        else -> "注意：两个密钥都是空的，导出文件里没有密钥"
                    }
                    "已导出 $nSettings 项设置 · $keyState"
                }.onSuccess { label.text = it }
                    .onFailure { label.text = "导出失败：${it.message ?: it.javaClass.simpleName}" }
            }
            REQ_IMPORT -> {
                label.text = "正在恢复…"
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("读不到所选文件")
                    ConfigBackup.importJson(prefs, text)
                }.onSuccess {
                    // Rebuild the screen so the restored values are visible
                    // immediately. Previously the boxes kept showing the old
                    // (empty) text, so a successful import looked like a no-op.
                    label.text = "已恢复：$it（正在刷新界面…）"
                    recreate()
                }.onFailure { label.text = "恢复失败：${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Palette.onAccent(this@SettingsActivity) else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(dp(9), if (on) accent else pillOff)
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Palette.onAccent(this@SettingsActivity) else sub)
            background = round(dp(10), if (initial) accent else Palette.pillOff(this@SettingsActivity))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Palette.onAccent(this@SettingsActivity) else sub)
            sw.background = round(dp(10), if (now) accent else Palette.pillOff(this@SettingsActivity))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Palette.card(this@SettingsActivity))
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Palette.hint(this@SettingsActivity))
        background = round(dp(8), Palette.field(this@SettingsActivity))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.onAccent(this@SettingsActivity)); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Palette.card(this@SettingsActivity), stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    /** Small outlined button that sits inline, under the field it acts on. */
    private fun rowBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(9), Palette.card(this@SettingsActivity), stroke = true)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /**
     * Scrollable single-choice list of models. Picking one writes it into [target]
     * (the model field) but does NOT save — the user still presses 保存 All, which
     * keeps the picker from mutating stored config behind their back.
     */
    private fun showModelPicker(
        models: List<ModelCatalog.Entry>,
        target: EditText,
        result: TextView
    ) {
        val current = target.text.toString().trim()
        val labels = models.map { if (it.id == current) "✓ ${it.label}" else it.label }.toTypedArray()
        val checked = models.indexOfFirst { it.id == current }
        android.app.AlertDialog.Builder(this)
            .setTitle("选择模型（共 ${models.size} 个）")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                target.setText(models[which].id)
                result.text = "已选：${models[which].id}（点「保存全部设置」生效）"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** DeepSeek's official API has no vision model; say so instead of a 400. */
        private const val GUARD_NO_VISION =
            "该接口不支持视觉（DeepSeek 官方没有 image_url），请换 OpenRouter 或通义兼容"

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_JUDGE = "jev_probe_scratch_judge"
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

        /** SAF request codes for the backup card's two pickers. */
        private const val REQ_EXPORT = 1001
        private const val REQ_IMPORT = 1002

        private const val REPO_URL = "https://github.com/Animal2404/jev-jarvis-qq"
    }
}
