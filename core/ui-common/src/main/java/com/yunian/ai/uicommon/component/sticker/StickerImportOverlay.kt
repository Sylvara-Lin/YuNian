package com.yunian.ai.uicommon.component.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yunian.ai.common.HardwareInfo
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.jellyEntrance
import com.yunian.ai.uicommon.theme.WeChatDarkCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 命名卡片统一圆角（CornerBasedShape，满足玻璃范式对 lens 的形状约束） */
private val OverlayCardShape = RoundedCornerShape(24.dp)

/** 卡片内容最大高度：键盘弹出 / 小屏时取 min(此值, 可用高度)，卡片内部滚动，不再被硬裁切出「空白横条」 */
private val OverlayCardMaxHeight = 520.dp

/** 雾气巡游周期（平时 ms/圈） */
private const val MARQUEE_PERIOD_MS = 2600

/** 确认脉冲时的提速周期（ms/圈） */
private const val MARQUEE_PULSE_PERIOD_MS = 700

/** 雾气基础亮度（0~1），确认脉冲时插值到 1 */
private const val MARQUEE_BASE_BRIGHTNESS = 0.7f

/**
 * 全屏液态玻璃导入过场（P0–P4 五阶段分镜）：
 *  P0 捕获 → 卡片从落点弹出（scale 0.6→1 + alpha 0→1，spring 0.75/380）；
 *  P1 全屏化 → morph 至屏幕中央（全部走 graphicsLayer，不改测量尺寸）；
 *  P2 命名 → 表情预览果冻入场（jellyEntrance）+ 命名表单 fade 渐入；
 *  P3 确认 → 预览片 scale 1→0.2 + alpha→0 飞向面板入口方向，卡片 fadeOut(150)，雾气脉冲增强；
 *  P4 收尾 → 由 StickerManager.version 触发面板刷新 + 新格子 jellyEntrance（面板侧实现）。
 *
 * 视觉：
 * - 遮罩为柔和暗化（亮色 35% / 暗色 55%，随主题），卡片为真玻璃观感：
 *   高光顶渐变 + 半透明底 + 细白描边；
 * - 雾气光效（替代旧版窄硬描边跑马灯）：屏幕四周一圈弥散雾气光晕绕屏巡游 +
 *   卡片表面一条柔和光带缓慢扫过（对齐 AI 生图等待气泡的高光扫过特效）；
 *   P3 确认脉冲时提速 + 增亮；
 * - 卡片高度受可用高度约束（BoxWithConstraints）+ 内部滚动 + imePadding：
 *   键盘弹出时整体上移避让且卡片不超过剩余空间，不再上下裁切 / 溢出。
 *
 * HardwareInfo.Tier.LOW → 全部退化为 alpha tween(120ms) 直切 + 静态卡片，无 blur / 形变 / jelly / 雾气。
 * 所有导入路径（拖拽 / 相册 / 文件）收口到本组件，行为一致。
 */
@Composable
fun StickerImportOverlay(
    visible: Boolean,
    previewUri: Uri?,
    suggestedName: String,
    isNameTaken: (String) -> Boolean,
    onConfirm: (name: String, semantic: String, aliases: List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 重命名场景直接传本地文件路径预览（与 previewUri 二选一） */
    previewPath: String? = null,
    title: String = "为这个表情起个名字",
    confirmText: String = "保存",
    initialSemantic: String = "",
    initialAliases: List<String> = emptyList(),
    currentName: String? = null,
) {
    val lowEnd = HardwareInfo.tier == HardwareInfo.Tier.LOW
    val context = LocalContext.current
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // P3 确认脉冲：确认瞬间雾气提速 + 增亮，随后随整体 fadeOut 淡出
    var confirming by remember { mutableStateOf(false) }

    // P2：加载预览位图（≤512px 降采样，防大图 OOM）
    LaunchedEffect(previewUri, previewPath) {
        previewBitmap = when {
            previewUri != null -> decodeUriSampled(context, previewUri)
            previewPath != null -> com.yunian.ai.common.StickerManager
                .getInstance(context).decodeSampledFile(previewPath)
            else -> null
        }
    }

    // P0/P1：卡片弹出 + 全屏化 morph（graphicsLayer，不改测量）；LOW 端直切
    val cardProgress = remember { Animatable(1f) }
    LaunchedEffect(visible, previewUri, previewPath) {
        if (visible && !lowEnd) {
            cardProgress.snapTo(0f)
            cardProgress.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 380f))
        } else {
            cardProgress.snapTo(1f)
        }
    }

    // 雾气巡游：角度 0→360 匀速循环；确认脉冲时提速（2600ms → 700ms/圈）+ 增亮
    val active = visible && !lowEnd
    val glowBoost by animateFloatAsState(
        targetValue = if (confirming) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "stickerMistGlow"
    )
    // 巡游周期随脉冲插值（量化到 100ms 步进，避免 LaunchedEffect 逐帧重启）
    val rawPeriod = MARQUEE_PERIOD_MS - (MARQUEE_PERIOD_MS - MARQUEE_PULSE_PERIOD_MS) * glowBoost
    val periodMs = ((rawPeriod / 100f).roundToInt() * 100).coerceAtLeast(200)

    val sweepAngle = remember { Animatable(0f) }
    LaunchedEffect(active, periodMs) {
        if (!active) return@LaunchedEffect
        while (true) {
            sweepAngle.snapTo(sweepAngle.value % 360f)
            sweepAngle.animateTo(
                targetValue = sweepAngle.value + 360f,
                animationSpec = tween(durationMillis = periodMs, easing = LinearEasing)
            )
        }
    }
    val brightness = MARQUEE_BASE_BRIGHTNESS + (1f - MARQUEE_BASE_BRIGHTNESS) * glowBoost

    // Apple Intelligence 风格边缘雾光（参考开源复刻配方：conic 多色闭环旋转 + blur 弥散层 + 呼吸）：
    // 色板蓝→紫→粉→橙首尾闭合，SweepGradient 旋转即多色光带绕屏流动，无头尾接缝
    val siriPalette = remember {
        intArrayOf(
            Color(0xFF5E9BFF).toArgb(), // 蓝
            Color(0xFFBF5AF2).toArgb(), // 紫
            Color(0xFFFF6482).toArgb(), // 粉
            Color(0xFFFFB340).toArgb(), // 橙
            Color(0xFF5E9BFF).toArgb()  // 蓝（首尾闭合 → 无缝循环）
        )
    }
    // 雾层与亮脊层共用色板、同角度旋转（CSS 复刻中 glow 与 border 同 --angle），各自持有 shader 实例
    val mistSweepShader = remember(siriPalette) {
        SweepGradient(0f, 0f, siriPalette, null)
    }
    val ridgeSweepShader = remember(siriPalette) {
        SweepGradient(0f, 0f, siriPalette, null)
    }
    val mistMatrix = remember { Matrix() }
    val mistStrokePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val mistScratchRect = remember { RectF() }
    // 真·雾：GPU RenderEffect blur（CSS filter:blur 等价物，API 31+）。
    // 光带画进独立离屏层再整体模糊 → 连续弥散、无任何环带痕迹；低版本退回多层描边近似
    val mistBlurRadiusPx = with(LocalDensity.current) { 22.dp.toPx() }
    val mistBlurEffect = remember(mistBlurRadiusPx) {
        if (Build.VERSION.SDK_INT >= 31) {
            RenderEffect.createBlurEffect(mistBlurRadiusPx, mistBlurRadiusPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        } else {
            null
        }
    }
    val mistSupportsRealBlur = Build.VERSION.SDK_INT >= 31
    // Siri 式呼吸：整体亮度 0.82~1.0 缓慢起伏（~3.2s 一个周期）
    val breatheTransition = rememberInfiniteTransition(label = "stickerMistBreathe")
    val breathePhase by breatheTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "stickerMistBreathePhase"
    )
    val breathe = 0.82f + 0.18f * sin(breathePhase * PI).toFloat()

    AnimatedVisibility(
        visible = visible,
        enter = if (lowEnd) fadeIn(tween(120)) else fadeIn(tween(180)),
        // P3：确认飞出（scale 1→0.2 + alpha→0，spring 0.7/420）
        exit = if (lowEnd) {
            fadeOut(tween(120))
        } else {
            scaleOut(
                targetScale = 0.2f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            ) + fadeOut(tween(150))
        },
        modifier = modifier
    ) {
        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 柔和暗化遮罩：玻璃卡片需要足够暗的底才能出材质对比
                .background(Color.Black.copy(alpha = if (isDark) 0.55f else 0.35f))
                .clickable(onClick = onDismiss)
        ) {
            // 全屏 Apple Intelligence 式边缘雾光（先声明 → 绘制在卡片之下，不遮挡内容）
            if (active) {
                if (mistSupportsRealBlur) {
                    // 真·雾层：宽多色光带经 GPU blur 整层模糊 → 连续弥散，无环带痕迹
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { renderEffect = mistBlurEffect }
                    ) {
                        drawMistGlowBand(
                            angleDeg = sweepAngle.value,
                            tone = brightness * breathe,
                            shader = mistSweepShader,
                            matrix = mistMatrix,
                            paint = mistStrokePaint,
                            scratchRect = mistScratchRect
                        )
                    }
                    // 清晰亮脊：不模糊的多色渐变细线（CSS 复刻的 sharp border 层）
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawMistRidgeLine(
                            angleDeg = sweepAngle.value,
                            tone = brightness * breathe,
                            shader = ridgeSweepShader,
                            matrix = mistMatrix,
                            paint = mistStrokePaint,
                            scratchRect = mistScratchRect
                        )
                    }
                } else {
                    // API < 31 降级：多层宽描边近似弥散
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawScreenMistLegacy(
                            angleDeg = sweepAngle.value,
                            tone = brightness * breathe,
                            mistShader = mistSweepShader,
                            ridgeShader = ridgeSweepShader,
                            matrix = mistMatrix,
                            paint = mistStrokePaint,
                            scratchRect = mistScratchRect
                        )
                    }
                }
            }

            // 键盘避让：imePadding 让居中卡片整体上移到键盘上方（insets 不可用时为 no-op，不影响布局）
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                // 卡片高度同时受「设计上限」与「剩余可用高度」约束：键盘弹出后卡片收缩为内部滚动，
                // 而不是被 Center 布局上下裁切出「只剩一个输入框」的错位观感
                val cardMaxHeight = minOf(OverlayCardMaxHeight, maxHeight - 24.dp).coerceAtLeast(200.dp)

                Box(
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .widthIn(max = 420.dp)
                        .graphicsLayer {
                            // P0/P1：从落点弹出至屏幕中央；只改绘制层，不动测量
                            if (lowEnd) {
                                alpha = 1f
                                scaleX = 1f
                                scaleY = 1f
                            } else {
                                val p = cardProgress.value
                                alpha = p
                                val s = 0.6f + 0.4f * p
                                scaleX = s
                                scaleY = s
                            }
                            this.transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .clip(OverlayCardShape)
                        // 聊天框同款真液态玻璃：vibrancy + blur + lens 边缘折射（backdrop 缺失时自动退化为磨砂底）
                        .drawGlass(
                            backdrop = LocalPageBackdrop.current,
                            shape = OverlayCardShape,
                            surfaceColor = if (isDark) {
                                WeChatDarkCard
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                            }
                        )
                        // 拦截卡片上的点击，避免误触遮罩关闭
                        .clickable(onClick = {})
                        // 限高 + 内部滚动：键盘弹出挤压可用空间时表单滚动，不再溢出裁切；
                        // padding 在 heightIn 之后：滚动视口四周固定留白（与原实现一致）
                        .heightIn(max = cardMaxHeight)
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val bitmap = previewBitmap
                        if (bitmap != null) {
                            // P2：表情预览果冻入场抖一下（LOW 端关闭）
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "表情预览",
                                modifier = Modifier
                                    .size(96.dp)
                                    .jellyEntrance(play = visible && !lowEnd),
                                contentScale = ContentScale.Fit
                            )
                        }
                        StickerNamingForm(
                            suggestedName = suggestedName,
                            isNameTaken = isNameTaken,
                            onConfirm = { name, semantic, aliases ->
                                confirming = true
                                onConfirm(name, semantic, aliases)
                            },
                            onCancel = onDismiss,
                            modifier = Modifier.padding(top = 8.dp),
                            title = title,
                            confirmText = confirmText,
                            initialSemantic = initialSemantic,
                            initialAliases = initialAliases,
                            currentName = currentName,
                        )
                    }

                    // 卡片表面雾光扫过：matchParentSize 叠加在内容之上，被卡片圆角裁切，
                    // 与 AI 生图等待气泡的「柔和高光斜向扫过」同款质感；随巡游角度循环
                    if (active) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            drawMistSweep(angleDeg = sweepAngle.value, brightness = brightness)
                        }
                    }
                }
            }
        }
    }
}

/** 雾层弥散描边（宽→窄）与对应 alpha：多层叠加模拟 blur glow，光从屏幕边缘向内平滑衰减 */
private val MistDiffuseLayers = listOf(
    58.dp to 0.035f,
    40.dp to 0.055f,
    25.dp to 0.085f,
    13.dp to 0.13f
)

/** 亮脊描边（贴边清晰渐变层，CSS 复刻中的 sharp border） */
private val MistRidgeLayers = listOf(
    6.dp to 0.42f,
    2.5.dp to 0.75f
)

/** 屏幕圆角近似值 */
private val ScreenCornerPx = 36.dp

/**
 * blur 雾层内容：两条宽多色光带（blur 前的「光源」，整层经 RenderEffect 模糊后变成连续弥散的雾）。
 * blur 会稀释 alpha，光带画得浓：宽层铺雾量、窄层给核心亮度。
 */
private fun DrawScope.drawMistGlowBand(
    angleDeg: Float,
    tone: Float,
    shader: SweepGradient,
    matrix: Matrix,
    paint: Paint,
    scratchRect: RectF,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    matrix.reset()
    matrix.postTranslate(cx, cy)
    matrix.postRotate(angleDeg, cx, cy)
    shader.setLocalMatrix(matrix)
    paint.shader = shader
    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val cornerBase = ScreenCornerPx.toPx()
        val layers = listOf(34.dp to 0.38f, 14.dp to 0.80f)
        for ((strokeDp, alpha) in layers) {
            val inset = strokeDp.toPx() / 2f
            scratchRect.set(inset, inset, size.width - inset, size.height - inset)
            paint.strokeWidth = strokeDp.toPx()
            paint.alpha = (alpha * tone * 255f).toInt().coerceIn(0, 255)
            nativeCanvas.drawRoundRect(scratchRect, cornerBase + inset, cornerBase + inset, paint)
        }
    }
}

/** 清晰亮脊层（不模糊）：贴边多色渐变细线，CSS 复刻中的 sharp border */
private fun DrawScope.drawMistRidgeLine(
    angleDeg: Float,
    tone: Float,
    shader: SweepGradient,
    matrix: Matrix,
    paint: Paint,
    scratchRect: RectF,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    matrix.reset()
    matrix.postTranslate(cx, cy)
    matrix.postRotate(angleDeg, cx, cy)
    shader.setLocalMatrix(matrix)
    paint.shader = shader
    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val cornerBase = ScreenCornerPx.toPx()
        val inset = 1.5.dp.toPx()
        scratchRect.set(inset, inset, size.width - inset, size.height - inset)
        paint.strokeWidth = 2.5.dp.toPx()
        paint.alpha = (0.75f * tone * 255f).toInt().coerceIn(0, 255)
        nativeCanvas.drawRoundRect(scratchRect, cornerBase, cornerBase, paint)
    }
}

/**
 * API < 31 降级：多层宽描边近似弥散（无 RenderEffect 时的保守形态）。
 * 配方同开源 CSS 复刻：宽描边 × 低 alpha 逐层衰减模拟 blur glow + 窄描边亮脊。
 */
private fun DrawScope.drawScreenMistLegacy(
    angleDeg: Float,
    tone: Float,
    mistShader: SweepGradient,
    ridgeShader: SweepGradient,
    matrix: Matrix,
    paint: Paint,
    scratchRect: RectF,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    matrix.reset()
    matrix.postTranslate(cx, cy)
    matrix.postRotate(angleDeg, cx, cy)
    mistShader.setLocalMatrix(matrix)
    ridgeShader.setLocalMatrix(matrix)

    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val cornerBase = ScreenCornerPx.toPx()

        paint.shader = mistShader
        for ((strokeDp, alpha) in MistDiffuseLayers) {
            val inset = strokeDp.toPx() / 2f
            scratchRect.set(inset, inset, size.width - inset, size.height - inset)
            paint.strokeWidth = strokeDp.toPx()
            paint.alpha = (alpha * tone * 255f).toInt().coerceIn(0, 255)
            nativeCanvas.drawRoundRect(scratchRect, cornerBase + inset, cornerBase + inset, paint)
        }

        paint.shader = ridgeShader
        for ((strokeDp, alpha) in MistRidgeLayers) {
            val inset = strokeDp.toPx() / 2f
            scratchRect.set(inset, inset, size.width - inset, size.height - inset)
            paint.strokeWidth = strokeDp.toPx()
            paint.alpha = (alpha * tone * 255f).toInt().coerceIn(0, 255)
            nativeCanvas.drawRoundRect(scratchRect, cornerBase + inset, cornerBase + inset, paint)
        }
    }
}

/**
 * 卡片表面雾光扫过：一条宽柔光带斜向掠过（与 AI 生图等待气泡的高光扫过同款质感，更宽更柔）。
 * 巡游角度 0→360 归一化为光带位置 -band → 1+band 循环；confirming 时随周期提速、亮度增强。
 */
private fun DrawScope.drawMistSweep(angleDeg: Float, brightness: Float) {
    val bandFraction = 0.55f
    val bandWidth = size.width * bandFraction
    val phase = (angleDeg % 360f) / 360f
    val start = phase * (1f + bandFraction * 2f) - bandFraction
    val x0 = start * (size.width + bandWidth) - bandWidth
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.07f * brightness),
                Color.White.copy(alpha = 0.14f * brightness),
                Color.Transparent
            ),
            start = Offset(x0, 0f),
            end = Offset(x0 + bandWidth, size.height)
        )
    )
}

/** Uri 预览图两遍解码：先读 bounds 再按 ≤512px 目标算 inSampleSize */
private suspend fun decodeUriSampled(context: Context, uri: Uri, maxDimension: Int = 512): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= maxDimension ||
                bounds.outHeight / (sampleSize * 2) >= maxDimension
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: OutOfMemoryError) {
            // MIUI/MTK 上超大/畸形图即便降采样仍可能 OOM（Error 非 Exception）；兜底返回 null 走占位
            null
        } catch (_: Exception) {
            null
        }
    }

/**
 * 从 Uri 猜一个建议名：DISPLAY_NAME 去扩展名、去协议方括号、≤20 字。
 * 相册 / 相机缓存名（纯数字如 1000066935、IMG_20240101 之类）对用户没有意义，
 * 判定为乱码时返回空串，让占位文案引导用户自己起名（修「预填 1000066935」问题）。
 */
fun suggestStickerName(context: Context, uri: Uri): String {
    return try {
        val rawName = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        val cleaned = rawName
            ?.substringBeforeLast('.')
            ?.replace(Regex("[\\[\\]\\n\\r\\t]"), "")
            ?.trim()
            ?.take(20)
            .orEmpty()
        if (looksLikeJunkStickerName(cleaned)) "" else cleaned
    } catch (_: Exception) {
        ""
    }
}

/** 乱码名判定：纯数字 / 不含字母与中文的符号串 / 微信导出·截图前缀（命中即乱码）/ 相机前缀+数字 */
internal fun looksLikeJunkStickerName(name: String): Boolean {
    if (name.isBlank()) return true
    if (name.all { it.isDigit() }) return true
    val hasCjk = name.any { it.code in 0x4E00..0x9FFF }
    val hasLetter = name.any { it.isLetter() }
    if (!hasCjk && !hasLetter) return true
    val upper = name.uppercase()
    // 微信导出（mmexport 20240101_abc.jpg 常带字母后缀）与截图类缓存名：前缀命中即乱码，不要求含数字
    if (upper.startsWith("MMEXPORT") || upper.startsWith("SCREENSHOT")) return true
    // 相机 / 文件管理器常见前缀：需带数字才判乱码，避免误杀「Image」这类正常英文词
    val prefixed = upper.startsWith("IMG") || upper.startsWith("IMAGE") ||
        upper.startsWith("WX") || upper.startsWith("PICTURE")
    return prefixed && name.any { it.isDigit() }
}
