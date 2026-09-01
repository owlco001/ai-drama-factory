package com.dramafactory.app.ui.components

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 统一选中态的筛选项芯片（替代各页各自 override colors/border 的碎片写法）。
 *
 * 选中视觉唯一来源：primaryContainer 底 / onPrimaryContainer 字 / primary 前导图标，
 * 边框选中=primary、未选=outlineVariant —— 与按钮、卡片的主色语言一致，深浅色下对比度达标。
 *
 * 此前 AssetsPage 三处显式 override 成这套色，ProjectsPage 两处"小说/剧本模式"却用 Material 默认
 * （secondaryContainer），同一组件两套视觉语言。收口为单一组件，后续新增筛选只调本件即可。
 */
@Composable
fun DramaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = scheme.primaryContainer,
            selectedLabelColor = scheme.onPrimaryContainer,
            selectedLeadingIconColor = scheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = if (selected) scheme.primary else scheme.outlineVariant,
        ),
    )
}
