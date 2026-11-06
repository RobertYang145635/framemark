package com.framemark.native.shell.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.framemark.native.library.LibraryFamilyGroup
import com.framemark.native.library.LibraryIndex
import com.framemark.native.library.LibraryVendorGroup
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import com.framemark.native.theme.FmType

/** Group key the vendor category uses for one family — the same formula as `LibraryIndex.groups`. */
fun phoneFamilyGroupKey(vendorKey: String, family: LibraryFamilyGroup): String =
    "vendor|$vendorKey|${family.key}"

/**
 * 系列目录 (report §7.5): every brand with its series and real counts; one tap jumps
 * the sheet to that series. The brand/series split is WP-L's
 * (`LibraryIndex.vendors` / `families(vendorKey)`); no name is invented here, and
 * a series without a firmware family keeps WP-L's "未在固件中证实" label.
 */
@Composable
fun PhoneSeriesDirectory(
    index: LibraryIndex,
    onDismiss: () -> Unit,
    onPick: (groupKey: String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FmRadii.sheet))
                .background(FmPalette.surface)
                .padding(bottom = FmSpacing.sm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = FmSpacing.lg, end = FmSpacing.sm, top = FmSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "系列目录",
                        style = FmType.titleSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = FmPalette.onSurface,
                    )
                    Text(
                        text = "点一个系列直接跳过去",
                        style = FmType.labelSmall.copy(fontSize = 12.sp),
                        color = FmPalette.onSurfaceMuted,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "关闭系列目录"
                            role = Role.Button
                        }
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "关闭",
                        style = FmType.labelSmall.copy(fontSize = 12.sp),
                        color = FmPalette.primaryStrong,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(FmSpacing.xs))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = FmSpacing.lg,
                    end = FmSpacing.lg,
                    bottom = FmSpacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                index.vendors.forEach { vendor ->
                    item(key = "v:${vendor.key}") {
                        VendorHeader(vendor)
                    }
                    items(
                        index.families(vendor.key),
                        key = { family -> "f:${vendor.key}:${family.key}" },
                    ) { family ->
                        FamilyRow(
                            family = family,
                            onClick = { onPick(phoneFamilyGroupKey(vendor.key, family)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VendorHeader(vendor: LibraryVendorGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = FmSpacing.sm, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = vendor.label,
            style = FmType.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = FmPalette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.padding(horizontal = 3.dp))
        Text(
            text = "${vendor.count} 个",
            style = FmType.labelSmall.copy(fontSize = 11.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun FamilyRow(family: LibraryFamilyGroup, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(FmRadii.input))
            .background(FmPalette.surfaceMuted)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    if (family.hasFormalFamily) family.label else "${family.label}，未在固件中证实"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = FmSpacing.md, vertical = FmSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = family.label,
                style = FmType.labelMedium.copy(fontSize = 13.sp),
                color = FmPalette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            family.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    text = note,
                    style = FmType.labelSmall.copy(fontSize = 11.sp),
                    color = FmPalette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = "${family.count} 个",
            style = FmType.labelSmall.copy(fontSize = 12.sp),
            color = FmPalette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}
