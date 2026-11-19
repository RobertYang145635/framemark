package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framemark.native.library.LibraryCard
import com.framemark.native.library.LibraryIndex
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType
import com.framemark.native.workflow.GhostButton

/**
 * 手机端 边框 工具的**落地面板**：边框页本身不是库。
 *
 * 配色方案 §4.1（2026-11-15）把这里收成一块**当前项**：
 *  1. 小缩略图 + 名称 + 数据自带的类别 / 品牌 / 系列 + 右侧次级「更换」；
 *  2. 还没有边框时：当前项写「未选边框」，**到此为止** —— 没有全宽主按钮，也没有
 *     一句「选了之后会怎样」的说明；
 *  3. 库有多少分类 / 多少张卡不再占面板，只在容器的库页头出现。
 *
 * 2026-11（UX 审查）：无边框那一屏的入口**只有画布上那一枚**「选择一个边框」
 * （`StagePlaceholder`，离照片最近）。这里以前还有一枚同样文案的全宽按钮 —— 同一个
 * 动作在一屏里出现两次、文案还不一样，用户读到的是两件事。说明句同理删掉：删掉它
 * 用户仍然知道该点哪里，那它就是多余的（判断标准就是这个）。
 *
 * 这样同一屏上「当前边框」的名称只有这一处；画布 chip 只保留手势提示，页头不再
 * 重复名字。库（分类翻页 + 2 列网格 + 搜索）仍只由「更换 / 选择一个边框」打开，
 * 是盖在画布上的覆盖层 [PhoneBorderLibraryOverlay]。
 *
 * **没有滚动容器**：缩略图边长由 [PhoneBorderPageState.panelLayout] 按这块面板的
 * 实际高度挑（72 / 64 / 56 dp），所以当前项永远都在这一屏里。
 */
@Composable
fun PhoneBorderPanel(
    index: LibraryIndex?,
    /** 当前已应用的边框；面板与覆盖层的「当前」都读它。 */
    card: LibraryCard?,
    onRetry: (() -> Unit)?,
    /** 打开边框库覆盖层（[PhoneBorderLibraryOverlay] 由外壳画在整个编辑器之上）。 */
    onOpenLibrary: () -> Unit,
    /**
     * 「把当前设计用到全部 N 张」：和导出表单里那一枚是**同一个动作**（UX 审查
     * P1-3 把它从「只存在于导出表单」补到边框面板）。null 时不画。
     */
    onApplyToAll: (() -> Unit)? = null,
    /** 工程里的照片总数，只用来写「全部 N 张」。 */
    applyToAllCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (index == null) {
        LibraryUnavailable(onRetry = onRetry, modifier = modifier)
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 这块面板拿到的就是「底板高度 − 把手行 − 标题行」，所以预算直接对着它算。
        val layout = PhoneBorderPageState.panelLayout(
            availableHeightDp = maxHeight.value,
            hasCard = card != null,
            fontScale = LocalDensity.current.fontScale,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FmSpacing.lg, vertical = FmSpacing.sm),
            // 两种状态都只有一行当前项，所以内容居中；剩下的竖向空间均分成上下留白，
            // 不会像顶部对齐时那样在「更换」下面挂一条空带。
            verticalArrangement = Arrangement.Center,
        ) {
            CurrentBorderRow(
                card = card,
                tileDp = layout.tileDp.dp,
                onOpenLibrary = onOpenLibrary,
                onApplyToAll = onApplyToAll,
                applyToAllCount = applyToAllCount,
            )
        }
    }
}

/** 面板里的当前边框：小方图 + 名称 + 类别/系列 + 右侧「更换」（只有已选时才有）。 */
@Composable
private fun CurrentBorderRow(
    card: LibraryCard?,
    tileDp: Dp,
    onOpenLibrary: () -> Unit,
    onApplyToAll: (() -> Unit)?,
    applyToAllCount: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(tileDp)
                .clip(RoundedCornerShape(FmRadii.tile))
                .background(FmPalette.stage)
                .padding(FmSpacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            if (card != null) {
                PhoneFramePreview(card = card, modifier = Modifier.fillMaxSize())
            } else {
                Text("—", style = FmType.titleMedium, color = FmPalette.stageMuted)
            }
        }
        Spacer(Modifier.width(FmSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = PhoneBorderPageState.currentName(card),
                style = FmType.titleSmall,
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 没有边框时第二行**整行不画**（`currentDescription` 返回 null）：这一屏
            // 只靠画布上那一枚按钮表达下一步，不补说明句。
            val line = PhoneBorderPageState.currentDescription(
                card = card,
                description = card?.let { phoneCardDescription(it) },
            )
            if (line != null) {
                Spacer(Modifier.height(FmSpacing.xxs))
                Text(
                    text = line,
                    style = FmType.labelSmall,
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (card != null) {
            Spacer(Modifier.width(FmSpacing.sm))
            // 「用到全部 N 张」在「更换」左边，同一行右侧（UX 审查 P1-3）：动作以前只
            // 存在于导出表单里，用户在边框页挑完边框就想一次性套到全部照片，却找不到。
            // 它是一句话而不是第二枚描边按钮 —— 面板上只能有一个主次分明的主按钮，
            // 两个同样重的按钮会读成两件事（配色方案 §4.1 的理由）。
            if (onApplyToAll != null) {
                applyToAllLabel(applyToAllCount)?.let { label ->
                    ApplyToAllLink(label = label, count = applyToAllCount, onClick = onApplyToAll)
                    Spacer(Modifier.width(FmSpacing.xs))
                }
            }
            GhostButton(
                text = PhoneBorderPageState.CHANGE_LABEL,
                onClick = onOpenLibrary,
                minHeight = PhoneBorderPageState.PANEL_CHANGE_ACTION_HEIGHT_DP.dp,
            )
        }
    }
}

/**
 * 面板当前边框那一行的次级动作：把这一张的设计复制到工程里的全部照片。
 *
 * 与导出表单里的「把当前设计用到全部照片」调用**同一个**回调（外壳的
 * `confirmApplyAll` 确认弹窗），所以两处不会各做一套。
 * 触控高度沿用 44 dp 地板，与「更换」对齐；行高预算不变（面板仍是一行当前项）。
 */
@Composable
private fun ApplyToAllLink(label: String, count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = PhoneBorderPageState.PANEL_CHANGE_ACTION_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(FmRadii.pill))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "把当前设计用到全部 $count 张照片"
                role = Role.Button
            }
            .padding(horizontal = FmSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FmType.labelSmall,
            color = FmPalette.primaryStrong,
            maxLines = 1,
        )
    }
}

/**
 * 面板上「用到全部 N 张」的文案；只有一张照片时返回 null（不画这枚入口）。
 *
 * 纯函数，`PhoneBorderPanelEntryTest` 直接测：一个只有一张照片的工程不该出现
 * 「用到全部 1 张」这种自我矛盾的按钮。
 */
internal fun applyToAllLabel(count: Int): String? =
    if (count > 1) "用到全部 $count 张" else null

@Composable
private fun LibraryUnavailable(onRetry: (() -> Unit)?, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(FmSpacing.md),
        verticalArrangement = Arrangement.Center,
    ) {
        PhoneLibraryNote(if (onRetry == null) "正在载入边框库…" else "暂时无法载入边框库。", Modifier.fillMaxWidth())
        if (onRetry != null) {
            Spacer(Modifier.height(FmSpacing.sm))
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(FmRadii.pill))
                    .background(FmPalette.primarySoft)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = FmSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "重试",
                    style = FmType.labelMedium,
                    color = FmPalette.primaryStrong,
                )
            }
        }
    }
}
