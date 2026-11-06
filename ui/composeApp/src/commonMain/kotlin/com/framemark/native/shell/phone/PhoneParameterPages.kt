package com.framemark.native.shell.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.framemark.native.inspector.InspectorCardModel
import com.framemark.native.inspector.InspectorControl
import com.framemark.native.inspector.InspectorLoadState
import com.framemark.native.inspector.InspectorPage
import com.framemark.native.inspector.InspectorPageContent
import com.framemark.native.inspector.InspectorTabInfo
import com.framemark.native.inspector.LogoBrandPanel
import com.framemark.native.inspector.NativeTextSlotControls
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.DesignSession
import com.framemark.native.workflow.TextSlot
import com.framemark.native.workflow.isNativeTextSlotControl
import com.framemark.native.workflow.isUsableStaticTextControl

/**
 * The phone's parameter page for one WP-C tab ([InspectorTabInfo]).
 *
 * The content itself is WP-C's [InspectorPageContent] with `dense = true`, i.e. the
 * touch slider ([com.framemark.native.inspector.FmTouchSliderRow], whose hit row is
 * the single `FM_TOUCH_SLIDER_HEIGHT_DP` constant — P1 never defines its own touch
 * height). The two pages that are not a plain control list keep their own owner:
 *
 *  - 文字 renders the renderer's text rows ([NativeTextSlotControls]) when the card
 *    has them, so the existing "文字写回" fix and its focus highlight keep working;
 *    the declared text controls stay as the fallback when a card has no rows.
 *  - Logo is [LogoBrandPanel] on both platforms (WP-C report §2.2 item 6).
 *  - 水印 is passed in as [watermarkPane] because the watermark layer belongs to the
 *    editor (project write-back + preview), not to this file.
 */
@Composable
fun PhoneParameterPage(
    tab: InspectorTabInfo?,
    page: InspectorPage,
    model: InspectorCardModel?,
    session: DesignSession,
    inspectorState: InspectorLoadState,
    onRetry: () -> Unit,
    textSlots: List<TextSlot> = emptyList(),
    textOutputWidth: Int = 0,
    textOutputHeight: Int = 0,
    onTextSlotFocus: ((String?) -> Unit)? = null,
    watermarkPane: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    if (tab == null) {
        PanelNote(
            text = when (inspectorState) {
                InspectorLoadState.Idle, InspectorLoadState.Loading -> "正在载入可调参数…"
                is InspectorLoadState.Failed -> "暂时无法加载可调参数。"
                is InspectorLoadState.Ready -> "这套边框没有这一页的参数。"
            },
            modifier = modifier,
            onRetry = if (inspectorState is InspectorLoadState.Failed) onRetry else null,
        )
        return
    }
    if (page == InspectorPage.Watermark) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
        ) {
            watermarkPane?.invoke()
        }
        return
    }
    val hasRows = page == InspectorPage.Text && textSlots.isNotEmpty() && textOutputWidth > 0
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.xs),
    ) {
        when {
            page == InspectorPage.Logo && model != null ->
                LogoBrandPanel(model = model, session = session, dense = true)
            hasRows && model != null ->
                NativeTextSlotControls(
                    model = model,
                    slots = textSlots,
                    outputWidth = textOutputWidth,
                    outputHeight = textOutputHeight,
                    session = session,
                    onTextSlotFocus = onTextSlotFocus,
                    dense = true,
                )
            else -> InspectorPageContent(
                tab = tab.copy(controls = phonePageControls(tab)),
                session = session,
                dense = true,
            )
        }
        footer?.invoke()
    }
}

/**
 * Controls the phone page actually shows: no hidden rows, no blank labels, no
 * internal `params.slots` row, and no per-slot generated controls (those are drawn
 * by [NativeTextSlotControls] under their own row). Static text controls are only
 * kept when the card has no reliable renderer rows (same rule as WP-C).
 */
internal fun phonePageControls(tab: InspectorTabInfo): List<InspectorControl> =
    tab.controls
        .filterNot { it.hidden }
        .filter { it.label.isNotBlank() }
        .filterNot { it.param == "params.slots" }
        .filterNot { isNativeTextSlotControl(it) }
        .filter { tab.page != InspectorPage.Text || isUsableStaticTextControl(it) }
        .distinctBy { it.key }

@Composable
private fun PanelNote(text: String, modifier: Modifier, onRetry: (() -> Unit)?) {
    Column(
        modifier = modifier.fillMaxWidth().padding(FmSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FmSpacing.sm),
    ) {
        Text(text = text, style = FmType.bodyMedium, color = FmPalette.onSurfaceMuted)
        if (onRetry != null) {
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("重新加载参数", style = FmType.labelMedium, color = FmPalette.primaryStrong)
            }
        }
    }
}
