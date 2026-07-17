package io.github.ycfeng.ocdeck.feature.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.ui.component.openCodeTextFieldCursorBrush
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
internal fun ComposerParameterButton(
    text: String,
    onClick: () -> Unit,
    leadingIconRes: Int? = null,
    expanded: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .composerExpandableStateSemantics(expandable = enabled, expanded = expanded)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = if (expanded && enabled) OpenCodePalette.SurfaceActive else Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(
                    start = if (leadingIconRes == null) 8.dp else 6.dp,
                    top = 4.dp,
                    end = 4.dp,
                    bottom = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leadingIconRes?.let { iconRes ->
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = OpenCodePalette.IconMuted,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) OpenCodePalette.MutedText else OpenCodePalette.MutedText.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painter = painterResource(R.drawable.ic_opencode_chevron_down),
                contentDescription = null,
                tint = if (enabled) OpenCodePalette.IconMuted else OpenCodePalette.IconMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun AgentPickerPopup(
    agents: List<OpenCodeAgent>,
    selectedAgent: String?,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val popupOffsetY = with(LocalDensity.current) {
        -(48.dp + ComposerPopupGap).roundToPx()
    }

    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(0, popupOffsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .width(AgentPickerPopupWidth)
                .heightIn(max = AgentPickerPopupMaxHeight),
            shape = RoundedCornerShape(6.dp),
            color = OpenCodePalette.Panel,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
            shadowElevation = 3.dp,
        ) {
            LazyColumn(
                modifier = Modifier.selectableGroup(),
                contentPadding = PaddingValues(4.dp),
            ) {
                items(agents, key = { it.id }) { agent ->
                    AgentPickerOption(
                        label = agentParameterLabel(agent.id),
                        selected = agent.id == selectedAgent,
                        onClick = { onSelected(agent.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentPickerOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = if (selected) OpenCodePalette.PanelMuted else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) OpenCodePalette.SelectionBorder else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(start = 8.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = OpenCodePalette.MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_check),
                contentDescription = null,
                tint = OpenCodePalette.MutedText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun ModelPickerPopup(
    models: List<OpenCodeModel>,
    selectedModel: PromptModelSelection?,
    onDismiss: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onSelected: (OpenCodeModel) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            models
        } else {
            models.filter {
                it.name.contains(normalizedQuery, ignoreCase = true) ||
                    it.modelId.contains(normalizedQuery, ignoreCase = true) ||
                    it.providerName.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }
    val selectedItemIndex = remember(models, selectedModel) {
        modelPickerSelectedItemIndex(models, selectedModel)
    }
    val listState = rememberSelectionCenteredLazyListState(
        selectedItemIndex = selectedItemIndex.takeIf { query.isBlank() && filtered.isNotEmpty() },
    )
    val popupOffsetY = with(LocalDensity.current) {
        -(48.dp + ComposerPopupGap).roundToPx()
    }

    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(0, popupOffsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .width(ModelPickerPopupWidth)
                .heightIn(max = ModelPickerPopupMaxHeight),
            shape = RoundedCornerShape(6.dp),
            color = OpenCodePalette.Panel,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = ModelPickerPopupMaxHeight)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModelPickerToolbar(
                    query = query,
                    onQueryChange = { query = it },
                    onOpenProviders = onOpenProviders,
                    onOpenModels = onOpenModels,
                )
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.composer_model_empty),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenCodePalette.MutedText,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .selectableGroup(),
                    ) {
                        filtered.groupBy { it.providerName }.forEach { (providerName, providerModels) ->
                            item(key = "provider:$providerName") {
                                ModelProviderHeader(providerName)
                            }
                            items(providerModels, key = { "${it.providerId}/${it.modelId}" }) { model ->
                                val selected = selectedModel?.let {
                                    it.providerId == model.providerId && it.modelId == model.modelId
                                } == true
                                ModelPickerOption(
                                    model = model,
                                    selected = selected,
                                    onClick = { onSelected(model) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .background(OpenCodePalette.PanelMuted, RoundedCornerShape(6.dp))
                .border(1.dp, OpenCodePalette.ControlBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_search),
                contentDescription = null,
                tint = OpenCodePalette.IconMuted,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = OpenCodePalette.Text),
                cursorBrush = openCodeTextFieldCursorBrush(),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isBlank()) {
                            Text(
                                stringResource(R.string.composer_search_model),
                                style = MaterialTheme.typography.bodyLarge,
                                color = OpenCodePalette.MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        ModelPickerIconButton(
            iconRes = R.drawable.ic_opencode_plus,
            contentDescription = stringResource(R.string.composer_connect_provider),
            onClick = onOpenProviders,
        )
        ModelPickerIconButton(
            iconRes = R.drawable.ic_opencode_settings_gear,
            contentDescription = stringResource(R.string.composer_manage_models),
            onClick = onOpenModels,
        )
    }
}

@Composable
private fun ModelPickerIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = OpenCodePalette.IconMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ModelProviderHeader(providerName: String) {
    Text(
        text = providerName,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        color = OpenCodePalette.MutedText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ModelPickerOption(
    model: OpenCodeModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = if (selected) OpenCodePalette.PanelMuted else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) OpenCodePalette.SelectionBorder else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = model.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = OpenCodePalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (model.isFree) {
            ModelFreeBadge()
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_check),
                contentDescription = null,
                tint = OpenCodePalette.Text,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ModelFreeBadge() {
    Surface(
        shape = RoundedCornerShape(2.dp),
        color = OpenCodePalette.PanelMuted,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Text(
            text = stringResource(R.string.composer_model_free),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = OpenCodePalette.MutedText,
            maxLines = 1,
        )
    }
}

@Composable
internal fun VariantPickerPopup(
    variants: List<String>,
    selectedVariant: String?,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit,
) {
    val options = listOf(ReasoningOption(stringResource(R.string.composer_default_variant), null)) +
        variants.map { variant -> ReasoningOption(variant.toVariantDisplayLabel(), variant) }
    val listState = rememberSelectionCenteredLazyListState(
        selectedItemIndex = variantPickerSelectedItemIndex(variants, selectedVariant),
    )
    val popupOffsetY = with(LocalDensity.current) {
        -(48.dp + ComposerPopupGap).roundToPx()
    }

    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(0, popupOffsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .width(VariantPickerPopupWidth)
                .heightIn(max = VariantPickerPopupMaxHeight),
            shape = RoundedCornerShape(6.dp),
            color = OpenCodePalette.Panel,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
            shadowElevation = 3.dp,
        ) {
            Box(modifier = Modifier.padding(VariantPickerPopupPadding)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(VariantPickerListWidth)
                        .heightIn(max = VariantPickerPopupMaxHeight - VariantPickerPopupPadding * 2)
                        .selectableGroup(),
                ) {
                    items(options, key = { it.value?.let { value -> "variant:$value" } ?: "variant:null" }) { option ->
                        VariantPickerOption(
                            option = option,
                            selected = selectedVariant == option.value,
                            onClick = { onSelected(option.value) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantPickerOption(
    option: ReasoningOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = if (selected) OpenCodePalette.PanelMuted else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) OpenCodePalette.SelectionBorder else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(start = 8.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = option.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = OpenCodePalette.MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_check),
                contentDescription = null,
                tint = OpenCodePalette.MutedText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun agentParameterLabel(agentId: String): String = when (agentId) {
    "build" -> stringResource(R.string.composer_agent_build)
    "plan" -> stringResource(R.string.composer_agent_plan)
    else -> agentId
}

@Composable
private fun Modifier.composerExpandableStateSemantics(
    expandable: Boolean,
    expanded: Boolean,
): Modifier {
    if (!expandable) return this
    val description = stringResource(if (expanded) R.string.status_expanded else R.string.status_collapsed)
    return semantics { stateDescription = description }
}

private data class ReasoningOption(
    val label: String,
    val value: String?,
)

@Composable
private fun rememberSelectionCenteredLazyListState(selectedItemIndex: Int?): LazyListState {
    val listState = rememberLazyListState()
    var hasPositionedSelection by remember { mutableStateOf(false) }

    LaunchedEffect(selectedItemIndex) {
        if (hasPositionedSelection || selectedItemIndex == null) return@LaunchedEffect

        listState.scrollToItem(selectedItemIndex)
        val selectedItem = snapshotFlow {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.viewportEndOffset <= layoutInfo.viewportStartOffset) {
                null
            } else {
                layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedItemIndex }
            }
        }.filterNotNull().first()
        val layoutInfo = listState.layoutInfo
        listState.scrollBy(
            centeredLazyListScrollDelta(
                itemOffset = selectedItem.offset,
                itemSize = selectedItem.size,
                viewportStartOffset = layoutInfo.viewportStartOffset,
                viewportEndOffset = layoutInfo.viewportEndOffset,
            ),
        )
        hasPositionedSelection = true
    }

    return listState
}

internal fun modelPickerSelectedItemIndex(
    models: List<OpenCodeModel>,
    selectedModel: PromptModelSelection?,
): Int? {
    if (selectedModel == null) return null

    var itemIndex = 0
    models.groupBy { it.providerName }.values.forEach { providerModels ->
        itemIndex += 1
        providerModels.forEach { model ->
            if (model.providerId == selectedModel.providerId && model.modelId == selectedModel.modelId) {
                return itemIndex
            }
            itemIndex += 1
        }
    }
    return null
}

internal fun variantPickerSelectedItemIndex(
    variants: List<String>,
    selectedVariant: String?,
): Int? {
    if (selectedVariant == null) return 0
    val variantIndex = variants.indexOf(selectedVariant)
    return if (variantIndex >= 0) variantIndex + 1 else null
}

internal fun centeredLazyListScrollDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
): Float {
    val itemCenter = itemOffset + itemSize / 2f
    val viewportCenter = (viewportStartOffset + viewportEndOffset) / 2f
    return itemCenter - viewportCenter
}

private val ComposerPopupGap = 4.dp
private val AgentPickerPopupWidth = 96.dp
private val AgentPickerPopupMaxHeight = 160.dp
private val ModelPickerPopupWidth = 288.dp
private val ModelPickerPopupMaxHeight = 320.dp
private val VariantPickerPopupWidth = 104.dp
private val VariantPickerListWidth = 96.dp
private val VariantPickerPopupPadding = 4.dp
private val VariantPickerPopupMaxHeight = 160.dp
