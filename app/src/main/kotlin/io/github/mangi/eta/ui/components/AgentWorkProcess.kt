package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.markdown.StreamingMarkdownState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工作过程中默认只展示最新一条思考或工具，历史按需展开；结束后默认只留摘要。
 * 展开后仍是完整的分组时间线。这里只裁剪展示，不删除消息或改变模型上下文。
 * 工作过程与正文同样不加卡片外壳，让最终回答保持视觉主角。
 */
@Composable
internal fun AgentWorkProcess(
    id: String,
    messages: List<AgentChatMessageUi>,
    assistantOverlay: Boolean = false,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val running = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    // 默认不展开历史；本轮任务结束后自动收起，历史记录仍可再次手动展开。
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    var wasActive by remember(id) { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (wasActive && !active) expanded = false
        wasActive = active
    }
    // active 覆盖思考结束到下一工具开始之间的空档，避免依赖 running 造成反复开合。
    // 语音浮层也只展示最新一步，不再自动展开整段时间线。
    val showingLatest = active && !expanded
    val steps = remember(messages, expanded, active) {
        when {
            expanded -> messages.toWorkSteps()
            active -> {
                // ToolSummary 是批次摘要；有真实思考/工具记录时不要让摘要遮住最新步骤。
                val latest = messages.lastOrNull { it is ThinkingMessageUi || it is ToolActivityMessageUi }
                    ?: messages.lastOrNull()
                latest?.let { listOf(AgentWorkStep.Single(it)) }.orEmpty()
            }
            else -> emptyList()
        }
    }
    val bodyVisible = expanded || showingLatest

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (assistantOverlay) 12.dp else 16.dp, vertical = 2.dp),
    ) {
        AgentWorkHeader(
            messages = messages,
            working = active || running,
            expanded = expanded,
            showingLatest = showingLatest,
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(
            visible = bodyVisible,
            enter = fadeIn(tween(160)) + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        ) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                steps.forEachIndexed { index, step ->
                    key(step.key) {
                        AgentWorkStepNode(
                            step = step,
                            isLast = index == steps.lastIndex,
                            onOpenBrowser = onOpenBrowser,
                            currentBrowserMessageId = currentBrowserMessageId,
                            retainedStreamingStates = retainedStreamingStates,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentWorkHeader(
    messages: List<AgentChatMessageUi>,
    working: Boolean,
    expanded: Boolean,
    showingLatest: Boolean,
    onClick: () -> Unit,
) {
    val summary = remember(messages) { messages.workSummary() }
    val text = if (working) {
        stringResource(R.string.work_in_progress)
    } else {
        workSummaryText(summary)
    }
    val expandAction = stringResource(if (expanded) R.string.work_collapse else R.string.work_expand)
    val expandedState = stringResource(
        when {
            expanded -> R.string.work_history_expanded
            showingLatest -> R.string.work_latest_only
            else -> R.string.work_history_folded
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = expandAction, onClick = onClick)
            .semantics { stateDescription = expandedState },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(WorkGutterWidth), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    working -> Icons.Rounded.Lightbulb
                    else -> Icons.Rounded.Checklist
                },
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = when {
                    summary.failedCount > 0 -> StatusError
                    working -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
        Spacer(modifier = Modifier.width(WorkGutterGap))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerText(
                text = text,
                active = working,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            // 历史折叠不隐藏失败：即使已开始下一步，摘要仍明确保留失败计数。
            if (summary.failedCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.work_summary_failed,
                        summary.failedCount,
                        summary.failedCount,
                    ),
                    style = MiuixTheme.textStyles.footnote2,
                    color = StatusError,
                )
            }
        }
        ExpandChevron(
            expanded = expanded,
            // 整行已经提供按钮动作和展开状态，避免读屏重复播报装饰箭头。
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
        )
    }
}

@Composable
private fun workSummaryText(summary: AgentWorkSummary): String {
    val parts = buildList {
        if (summary.toolCount > 0) {
            add(pluralStringResource(R.plurals.work_summary_tools, summary.toolCount, summary.toolCount))
            add(pluralStringResource(R.plurals.work_summary_files, summary.modifiedFileCount, summary.modifiedFileCount))
            add(pluralStringResource(R.plurals.work_summary_commands, summary.commandCount, summary.commandCount))
        }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.work_completed) }
}

@Composable
private fun AgentWorkStepNode(
    step: AgentWorkStep,
    isLast: Boolean,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    retainedStreamingStates: Map<String, StreamingMarkdownState>,
) {
    when (step) {
        is AgentWorkStep.ToolGroup -> WorkTimelineNode(
            icon = iconForTool(step.toolName),
            tone = when {
                step.running -> WorkNodeTone.Running
                step.failedCount > 0 -> WorkNodeTone.Failed
                else -> WorkNodeTone.Done
            },
            isLast = isLast,
        ) {
            ToolGroupRow(group = step, onOpenBrowser = onOpenBrowser)
        }

        is AgentWorkStep.Single -> when (val message = step.message) {
            is ToolActivityMessageUi -> WorkTimelineNode(
                icon = iconForTool(message.toolName),
                tone = message.status.nodeTone(),
                isLast = isLast,
            ) {
                ToolActivityRow(
                    message = message,
                    onOpenBrowser = onOpenBrowser,
                    showBrowserShortcut = message.id == currentBrowserMessageId,
                )
            }

            is ThinkingMessageUi -> WorkTimelineNode(
                icon = Icons.Rounded.Lightbulb,
                tone = if (message.isStreaming) WorkNodeTone.Running else WorkNodeTone.Done,
                isLast = isLast,
            ) {
                ThinkingRow(
                    message = message,
                    retainedStreamingState = retainedStreamingStates[message.id],
                    compact = true,
                )
            }

            is ToolSummaryMessageUi -> WorkTimelineNode(
                icon = Icons.Rounded.Build,
                tone = WorkNodeTone.Done,
                isLast = isLast,
            ) {
                ChatMessageItem(
                    message = message,
                    onSuggestionClick = {},
                    onRunTraceClick = {},
                    onOpenBrowser = onOpenBrowser,
                    showBrowserShortcut = false,
                    compact = true,
                )
            }

            else -> Unit
        }
    }
}

internal enum class WorkNodeTone { Running, Done, Failed }

internal fun ToolActivityStatusUi.nodeTone(): WorkNodeTone = when (this) {
    ToolActivityStatusUi.Running -> WorkNodeTone.Running
    ToolActivityStatusUi.Failed -> WorkNodeTone.Failed
    ToolActivityStatusUi.Success, ToolActivityStatusUi.Unknown -> WorkNodeTone.Done
}

/**
 * 时间线节点：左侧固定宽度的槽位放图标徽章，连线在绘制阶段画出，不参与测量。
 * 节点默认向上接到工作过程标题图标，最后一个节点不向下延伸。
 */
@Composable
internal fun WorkTimelineNode(
    icon: ImageVector,
    tone: WorkNodeTone,
    isLast: Boolean,
    connectTop: Boolean = true,
    content: @Composable () -> Unit,
) {
    val lineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = WorkGutterWidth.toPx() / 2f
                val stroke = 1.dp.toPx()
                val gap = 3.dp.toPx()
                val nodeTop = (WorkRowHeight - WorkNodeSize).toPx() / 2f
                val nodeBottom = nodeTop + WorkNodeSize.toPx()
                if (connectTop) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, nodeTop - gap), stroke)
                }
                if (!isLast) {
                    drawLine(lineColor, Offset(x, nodeBottom + gap), Offset(x, size.height), stroke)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .width(WorkGutterWidth)
                .height(WorkRowHeight),
            contentAlignment = Alignment.Center,
        ) {
            WorkNodeBadge(icon = icon, tone = tone)
        }
        Spacer(modifier = Modifier.width(WorkGutterGap))
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun WorkNodeBadge(icon: ImageVector, tone: WorkNodeTone) {
    val (background, tint) = when (tone) {
        WorkNodeTone.Running -> MiuixTheme.colorScheme.primary.copy(alpha = 0.14f) to
            MiuixTheme.colorScheme.primary
        WorkNodeTone.Failed -> StatusError.copy(alpha = 0.12f) to StatusError
        WorkNodeTone.Done -> MiuixTheme.colorScheme.surfaceContainer to
            MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val pulse = rememberDrawPhasePulse(active = tone == WorkNodeTone.Running)
    Box(
        modifier = Modifier
            .size(WorkNodeSize)
            .graphicsLayer { alpha = pulse?.value ?: 1f }
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = tint,
        )
    }
}

/** 呼吸动画只返回 State，由调用方在 graphicsLayer 中读取，帧间不触发重组。 */
@Composable
internal fun rememberDrawPhasePulse(active: Boolean): State<Float>? {
    if (!active) return null
    val transition = rememberInfiniteTransition(label = "work_node_pulse")
    return transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "work_node_pulse_alpha",
    )
}

/**
 * 进行中的标题用一道扫过的高光提示“正在发生”。高光在绘制阶段以 SrcAtop 叠在字形上，
 * 动画只使绘制失效；离屏层限定在这一行文字范围内。
 */
@Composable
internal fun ShimmerText(
    text: String,
    active: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shimmerModifier = if (active) {
        val highlight = MiuixTheme.colorScheme.onSurface
        val transition = rememberInfiniteTransition(label = "work_shimmer")
        val progress = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_600, easing = LinearEasing),
            ),
            label = "work_shimmer_progress",
        )
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val band = maxOf(size.width * 0.35f, 72.dp.toPx())
                val center = -band + (size.width + band * 2f) * progress.value
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        startX = center - band,
                        endX = center + band,
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            }
    } else {
        Modifier
    }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(shimmerModifier),
    )
}

@Composable
internal fun ExpandChevron(
    expanded: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "expand_chevron",
    )
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = contentDescription,
        modifier = modifier
            .size(14.dp)
            .graphicsLayer { rotationZ = rotation },
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
    )
}

/** 时间线标题行与节点徽章共用的几何尺寸，保证图标中心与首行文字中线对齐。 */
internal val WorkRowHeight: Dp = 32.dp
internal val WorkGutterWidth: Dp = 22.dp
internal val WorkGutterGap: Dp = 8.dp
private val WorkNodeSize: Dp = 20.dp
