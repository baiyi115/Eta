package io.github.mangi.eta.ui.screens.mcp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.ui.components.ExpandChevron
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Name row expands details; the independent switch never expands/collapses them. */
@Composable
internal fun McpToolPreference(
    serverId: String,
    tool: McpToolDefinition,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable(serverId, tool.name) { mutableStateOf(false) }
    val title = tool.title.ifBlank { tool.name }
    val expandAction = stringResource(if (expanded) R.string.mcp_collapse_tool_description else R.string.mcp_expand_tool_description)
    val expandedState = stringResource(if (expanded) R.string.mcp_description_expanded else R.string.mcp_description_collapsed)
    val switchDescription = stringResource(R.string.mcp_tool_switch_accessibility, title)
    Column {
        BasicComponent(
            title = title,
            modifier = Modifier.semantics { stateDescription = expandedState },
            onClick = { expanded = !expanded },
            endActions = {
                ExpandChevron(expanded = expanded, contentDescription = expandAction)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .semantics { contentDescription = switchDescription },
                )
            },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mcp_tool_name_label, tool.name),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                    Text(
                        text = stringResource(if (tool.readOnlyHint == true) R.string.mcp_tool_read_only_hint else R.string.mcp_may_modify_data),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                    Text(
                        text = tool.description.ifBlank { stringResource(R.string.mcp_no_tool_description) },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }
    }
}
