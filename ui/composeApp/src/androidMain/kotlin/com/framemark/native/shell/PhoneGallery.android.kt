package com.framemark.native.shell

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.framemark.native.shell.phone.GallerySelection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectDragGestures
import java.util.TimeZone
import com.framemark.native.shell.phone.GalleryDateGroups
import com.framemark.native.theme.FmPalette
import com.framemark.native.theme.FmRadii
import com.framemark.native.theme.FmSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自建相册（2026-11-21）。
 *
 * 实现要点（对照系统选择器与同类 App 的通行做法）：
 *  1. **数据**：`MediaStore.Images` 查询，按 `DATE_ADDED DESC`，分页取（每页 120 张，
 *     滚到剩 30 张时再取下一页）。只查需要的列。
 *  2. **缩略图**：API 29+ 用 `ContentResolver.loadThumbnail`（官方推荐，比老的
 *     `MediaStore.Images.Thumbnails` 更可控），低版本退回采样解码；解码结果进
 *     [ThumbnailCache]（按内存的 1/8 做 LRU）。
 *  3. **权限**：33+ 用 `READ_MEDIA_IMAGES`，34+ 再加 `READ_MEDIA_VISUAL_USER_SELECTED`
 *     以支持「部分照片访问」；≤32 用 `READ_EXTERNAL_STORAGE`。用户拒绝时不阻塞：给一句
 *     说明 + 「用系统选择器」的出口（那条路不需要任何权限）。
 *  4. **多选**：勾选角标是**顺序号**（[GallerySelection]），上限与系统选择器一致。
 *  5. 导入不在这里做：选完把 `content://` 交回宿主，走与系统选择器完全相同的导入链。
 */
@Composable
actual fun PhoneGalleryOverlay(
    visible: Boolean,
    onCancel: () -> Unit,
    onImport: (List<String>) -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection = remember { GallerySelection(MAX_SELECT) }
    // id -> content:// 地址：跨相册分组切换时也要能拼回地址（选中项不属于当前列表）。
    val uriById = remember { mutableStateMapOf<String, String>() }
    var selectionVersion by remember { mutableStateOf(0) }
    var permission by remember { mutableStateOf(readPermissionState(context)) }
    var items by remember { mutableStateOf(listOf<GalleryPhoto>()) }
    // 「今天 / 昨天 / 11月20日」分组：网格里的日期标题 + 吸顶标签 + 快速滚动气泡都用它。
    val zoneOffset = remember {
        (TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L)
    }
    val nowSeconds = remember { System.currentTimeMillis() / 1000L }
    var entries by remember { mutableStateOf<List<GalleryDateGroups.Entry>>(emptyList()) }
    val gridState = rememberLazyGridState()
    val photoById = remember { mutableStateMapOf<String, GalleryPhoto>() }
    var bucket by remember { mutableStateOf<GalleryBucket?>(null) }
    var buckets by remember { mutableStateOf(listOf<GalleryBucket>()) }
    var loading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var albumSheetOpen by remember { mutableStateOf(false) }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permission = readPermissionState(context)
    }

    // 打开即按当前权限加载；没有授权就先问。
    LaunchedEffect(Unit) {
        if (permission == GalleryPermission.Missing) {
            requestPermissions.launch(requiredPermissions())
        }
    }
    LaunchedEffect(permission, bucket?.id) {
        if (permission == GalleryPermission.Missing) return@LaunchedEffect
        loading = true
        val page = withContext(Dispatchers.IO) { MediaGallery.query(context, bucket?.id, 0, PAGE_SIZE) }
        buckets = withContext(Dispatchers.IO) { MediaGallery.buckets(context) }
        items = page
        photoById.clear()
        page.forEach { photoById[it.id] = it }
        entries = GalleryDateGroups.entries(
            photos = page.map { it.id to it.dateSec },
            nowSeconds = nowSeconds,
            zoneOffsetSeconds = zoneOffset,
        )
        gridState.scrollToItem(0)
        done = page.isEmpty()
        loading = false
    }

    LaunchedEffect(gridState, items.size, permission) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (permission == GalleryPermission.Missing) return@collect
                if (loading || done) return@collect
                if (last < items.size - 30) return@collect
                loading = true
                val next = withContext(Dispatchers.IO) {
                    MediaGallery.query(context, bucket?.id, items.size, PAGE_SIZE)
                }
                val fresh = next.filter { candidate -> items.none { it.id == candidate.id } }
                items = items + fresh
                fresh.forEach { photoById[it.id] = it }
                entries = GalleryDateGroups.append(
                    existing = entries,
                    more = fresh.map { it.id to it.dateSec },
                    nowSeconds = nowSeconds,
                    zoneOffsetSeconds = zoneOffset,
                )
                done = next.isEmpty()
                loading = false
            }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(FmPalette.canvas)
                // Dialog 用了 decorFitsSystemWindows = false：状态栏/导航栏的内边距要自己留，
                // 否则「取消 / 选择照片 / 系统选择器」会压在状态栏图标上。
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "取消",
                    color = FmPalette.onSurfaceSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                // 相册（大文件夹）默认折叠成一行标题，点开才是竖向列表 —— 不再用横滑 chips
                // 占掉一整行（用户 2026-11-21 对照酷安指出）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable { albumSheetOpen = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = bucket?.name ?: "最近照片",
                        color = FmPalette.onSurface,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = "▾", color = FmPalette.onSurfaceMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (selection.isEmpty) "系统选择器" else "添加 ${selection.size}",
                    color = if (selection.isEmpty) FmPalette.onSurfaceMuted else FmPalette.primary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FmRadii.chip))
                        .clickable {
                            if (selection.isEmpty) {
                                onCancel()
                                com.framemark.native.workflow.WorkflowEnv.photoPicker?.invoke()
                            } else {
                                onImport(selection.ordered().mapNotNull { uriById[it] })
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            when (permission) {
                GalleryPermission.Missing, GalleryPermission.Limited -> {
                    if (permission == GalleryPermission.Missing) {
                        GalleryPermissionNotice(
                            text = "需要读取相册才能浏览全部照片",
                            action = "去授权",
                            onAction = { requestPermissions.launch(requiredPermissions()) },
                            secondary = "用系统选择器",
                            onSecondary = {
                                onCancel()
                                com.framemark.native.workflow.WorkflowEnv.photoPicker?.invoke()
                            },
                        )
                        return@Column
                    }
                }
                GalleryPermission.Full -> Unit
            }


            if (albumSheetOpen) {
                AlbumSheet(
                    buckets = buckets,
                    current = bucket,
                    total = buckets.sumOf { it.count },
                    onPick = { picked ->
                        bucket = picked
                        albumSheetOpen = false
                    },
                    onDismiss = { albumSheetOpen = false },
                )
            }

            // 选中状态是普通对象，读一次 tick 才能让网格与页脚随勾选重组。
            val selectionTick = selectionVersion
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = entries,
                        key = { it.key },
                        span = { entry ->
                            if (entry is GalleryDateGroups.Entry.Header) {
                                androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                            } else {
                                androidx.compose.foundation.lazy.grid.GridItemSpan(1)
                            }
                        },
                    ) { entry ->
                        when (entry) {
                            is GalleryDateGroups.Entry.Header -> GalleryDayHeader(entry)
                            is GalleryDateGroups.Entry.Item -> {
                                val photo = photoById[entry.photoId]
                                if (photo != null) {
                                    val position = selection.positionOf(photo.id)
                                    GalleryTile(
                                        context = context,
                                        photo = photo,
                                        position = position,
                                        tick = selectionTick,
                                        onClick = {
                                            val wasSelected = selection.contains(photo.id)
                                            val changed = selection.toggle(photo.id)
                                            if (changed) {
                                                if (wasSelected) uriById.remove(photo.id)
                                                else uriById[photo.id] = photo.uri.toString()
                                                selectionVersion++
                                            } else if (selection.isFull) {
                                                notice = "最多一次选 ${selection.limit} 张"
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // 吸顶日期：跟随滚动显示当前这一组的日期（网格没有内建 sticky header）。
                val sticky = remember(entries) {
                    androidx.compose.runtime.derivedStateOf {
                        GalleryDateGroups.headerAt(entries, gridState.firstVisibleItemIndex)
                    }
                }
                sticky.value?.let { header ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 不透明：网格里的日期标题会从下面滚过去，半透明会叠成两层字。
                            .background(FmPalette.canvas)
                            .padding(horizontal = FmSpacing.lg, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = header.label, color = FmPalette.onSurfaceSecondary, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(text = "${header.photos} 张", color = FmPalette.onSurfaceMuted, fontSize = 11.sp)
                    }
                }
                // 右侧快速滚动：按住拖动直接跳到对应位置，拖动时显示时间气泡。
                var scrubIndex by remember { mutableStateOf<Int?>(null) }
                val scopeForScrub = rememberCoroutineScope()
                val thumbFraction = androidx.compose.runtime.derivedStateOf {
                    if (entries.size <= 1) 0f
                    else (gridState.firstVisibleItemIndex.toFloat() / (entries.size - 1)).coerceIn(0f, 1f)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(40.dp)
                        .fillMaxHeight()
                        .pointerInput(entries.size) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val fraction = (offset.y / size.height).coerceIn(0f, 1f)
                                    scrubIndex = (fraction * (entries.size - 1).coerceAtLeast(0)).toInt()
                                    scopeForScrub.launch { gridState.scrollToItem(scrubIndex ?: 0) }
                                },
                                onDrag = { change, _ ->
                                    val fraction = (change.position.y / size.height).coerceIn(0f, 1f)
                                    val target = (fraction * (entries.size - 1).coerceAtLeast(0)).toInt()
                                    if (target != scrubIndex) {
                                        scrubIndex = target
                                        scopeForScrub.launch { gridState.scrollToItem(target) }
                                    }
                                },
                                onDragEnd = { scrubIndex = null },
                                onDragCancel = { scrubIndex = null },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 可见滑块：告诉用户「这里可以拖」，也显示当前大致位置。
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val trackHeight = maxHeight
                        val thumbHeight = 56.dp
                        val travel = (trackHeight - thumbHeight).coerceAtLeast(0.dp)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(vertical = 4.dp)
                                .width(6.dp)
                                .fillMaxHeight(),
                        ) {
                            Box(
                                modifier = Modifier
                                    .offset(y = travel * thumbFraction.value)
                                    .align(Alignment.TopCenter)
                                    .width(6.dp)
                                    .height(thumbHeight)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(FmPalette.onSurfaceMuted.copy(alpha = 0.55f)),
                            )
                        }
                    }
                    if (scrubIndex != null) {
                        val label = GalleryDateGroups.headerAt(entries, scrubIndex ?: 0)?.label.orEmpty()
                        // 气泡画在条带**外面**：条带只有 40dp 宽，放里面会把日期挤成竖排。
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 52.dp)
                                .clip(RoundedCornerShape(FmRadii.chip))
                                .background(FmPalette.surface)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = label,
                                color = FmPalette.onSurface,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                notice?.let { text ->
                    Text(
                        text = text,
                        color = FmPalette.onSurfaceSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(FmSpacing.lg)
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(FmPalette.surface)
                            .clickable { notice = null }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // 页脚也要随勾选重组（选中数量与按钮文案）。
            @Suppress("UNUSED_EXPRESSION")
            selectionTick
            if (selection.isEmpty) {
                Text(
                    text = "点一下选中，再点一下取消",
                    color = FmPalette.onSurfaceMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(FmSpacing.md),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(FmSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已选 ${selection.size} / ${selection.limit}",
                        color = FmPalette.onSurfaceMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { selection.clear(); selectionVersion++ },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "添加 ${selection.size} 张",
                        color = FmPalette.onPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(FmRadii.chip))
                            .background(FmPalette.primary)
                            .clickable { onImport(selection.ordered().mapNotNull { uriById[it] }) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

actual fun phoneGallerySupported(): Boolean = true

private const val MAX_SELECT = 9
private const val PAGE_SIZE = 120
private const val THUMB_EDGE_PX = 320

private enum class GalleryPermission { Full, Limited, Missing }

private data class GalleryPhoto(val id: String, val uri: Uri, val bucket: String?, val dateSec: Long)
private data class GalleryBucket(val id: Long, val name: String, val count: Int, val cover: Uri?)

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun readPermissionState(context: Context): GalleryPermission = when {
    Build.VERSION.SDK_INT >= 34 -> when {
        granted(context, Manifest.permission.READ_MEDIA_IMAGES) -> GalleryPermission.Full
        granted(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") -> GalleryPermission.Limited
        else -> GalleryPermission.Missing
    }
    Build.VERSION.SDK_INT >= 33 -> if (granted(context, Manifest.permission.READ_MEDIA_IMAGES)) {
        GalleryPermission.Full
    } else {
        GalleryPermission.Missing
    }
    else -> if (granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) {
        GalleryPermission.Full
    } else {
        GalleryPermission.Missing
    }
}

/** MediaStore 查询与缩略图；只有 IO 线程调用（[query]/[buckets]）或内部线程池（[thumbnail]）。 */
private object MediaGallery {

    fun query(context: Context, bucketId: Long?, offset: Int, limit: Int): List<GalleryPhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID}=?" else null
        val args = bucketId?.let { arrayOf(it.toString()) }
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val queryArgs = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                    putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
                }
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    queryArgs,
                    null,
                )
            } else {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "$sort LIMIT $limit OFFSET $offset",
                )
            }?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        add(
                            GalleryPhoto(
                                id = id.toString(),
                                uri = uri,
                                bucket = bucketColumn.takeIf { it >= 0 && !cursor.isNull(it) }
                                    ?.let { cursor.getString(it) },
                                dateSec = if (dateColumn >= 0 && !cursor.isNull(dateColumn)) {
                                    cursor.getLong(dateColumn)
                                } else {
                                    0L
                                },
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse { emptyList() }
    }

    /** 相册列表：名称、张数、封面（该相册最新一张）。按张数从多到少。 */
    fun buckets(context: Context): List<GalleryBucket> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val names = LinkedHashMap<Long, String>()
                val counts = HashMap<Long, Int>()
                val covers = HashMap<Long, Uri>()
                var scanned = 0
                while (cursor.moveToNext() && scanned < 8000) {
                    scanned++
                    val bucketId = cursor.getLong(bucketColumn)
                    val name = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                        cursor.getString(nameColumn)
                    } else {
                        null
                    } ?: continue
                    names[bucketId] = name
                    counts[bucketId] = (counts[bucketId] ?: 0) + 1
                    if (covers[bucketId] == null) {
                        val photoId = cursor.getLong(idColumn)
                        covers[bucketId] = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            photoId,
                        )
                    }
                }
                names.map { (id, name) ->
                    GalleryBucket(id, name, counts[id] ?: 0, covers[id])
                }.sortedByDescending { it.count }
            }.orEmpty()
        }.getOrElse { emptyList() }
    }

    fun thumbnail(context: Context, photo: GalleryPhoto): ImageBitmap? = runCatching {
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(photo.uri, Size(THUMB_EDGE_PX, THUMB_EDGE_PX), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                photo.id.toLong(),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
        bitmap?.asImageBitmap()
    }.getOrNull()
}

/** 缩略图 LRU：按可用内存的 1/8 计，键是照片 id + 期望边长。 */
private object ThumbnailCache {
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, value: ImageBitmap) { cache.put(key, value) }
}

/**
 * 相册（大文件夹）列表：底部弹出、竖向滚动。
 *
 * 用户 2026-11-21 对照酷安指出：相册应该是折叠的一行标题，点开再竖向选，而不是一直横滑。
 * 每行给「封面 + 名称 + 张数 + 当前勾选」，第一行是「最近照片」（全部）。
 */
@Composable
private fun AlbumSheet(
    buckets: List<GalleryBucket>,
    current: GalleryBucket?,
    total: Int,
    onPick: (GalleryBucket?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(FmPalette.scrim.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                // 主题里的 surface 是半透明玻璃色：先铺一层画布色，否则网格会透上来。
                .background(FmPalette.canvas)
                .background(FmPalette.surface),
        ) {
            Text(
                text = "选择相册",
                color = FmPalette.onSurface,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = FmSpacing.lg, vertical = FmSpacing.md),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = FmSpacing.md)) {
                item {
                    AlbumRow(
                        name = "最近照片",
                        count = total,
                        selected = current == null,
                        cover = buckets.firstOrNull()?.cover,
                        onClick = { onPick(null) },
                    )
                }
                items(buckets.size) { index ->
                    val entry = buckets[index]
                    AlbumRow(
                        name = entry.name,
                        count = entry.count,
                        selected = current?.id == entry.id,
                        cover = entry.cover,
                        onClick = { onPick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    name: String,
    count: Int,
    selected: Boolean,
    cover: Uri?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = FmSpacing.lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumCover(cover)
        Spacer(Modifier.width(FmSpacing.md))
        Text(
            text = name,
            color = if (selected) FmPalette.primary else FmPalette.onSurface,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = "$count", color = FmPalette.onSurfaceMuted, fontSize = 12.sp)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(text = "✓", color = FmPalette.primary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AlbumCover(uri: Uri?) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val key = "album:${uri}"
        ThumbnailCache.get(key)?.let { bitmap = it; return@LaunchedEffect }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, Size(96, 96), null)?.asImageBitmap()
                } else {
                    null
                }
            }.getOrNull()
        }
        if (loaded != null) {
            ThumbnailCache.put(key, loaded)
            bitmap = loaded
        }
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FmPalette.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun GalleryDayHeader(header: GalleryDateGroups.Entry.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FmSpacing.lg, end = FmSpacing.lg, top = FmSpacing.md, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = header.label, color = FmPalette.onSurfaceSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(text = "${header.photos} 张", color = FmPalette.onSurfaceMuted, fontSize = 11.sp)
    }
}

@Composable
private fun GalleryTile(
    context: Context,
    photo: GalleryPhoto,
    position: Int,
    /** 只用于让父级在勾选变化时重组这一格（值本身不参与绘制）。 */
    tick: Int,
    onClick: () -> Unit,
) {
    @Suppress("UNUSED_PARAMETER") val selectionTick = tick
    val cacheKey = "${photo.id}@$THUMB_EDGE_PX"
    var bitmap by remember(photo.id) { mutableStateOf(ThumbnailCache.get(cacheKey)) }
    LaunchedEffect(photo.id) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { MediaGallery.thumbnail(context, photo) }
        if (loaded != null) {
            ThumbnailCache.put(cacheKey, loaded)
            bitmap = loaded
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(FmPalette.surfaceMuted)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (position > 0) {
            Box(
                modifier = Modifier.fillMaxSize().background(FmPalette.scrim.copy(alpha = 0.28f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(FmPalette.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "$position", color = FmPalette.onPrimary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BucketChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) FmPalette.onPrimary else FmPalette.onSurfaceSecondary,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(FmRadii.chip))
            .background(if (selected) FmPalette.primary else FmPalette.surfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun GalleryPermissionNotice(
    text: String,
    action: String,
    onAction: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(FmSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, color = FmPalette.onSurfaceSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(FmSpacing.md))
        Text(
            text = action,
            color = FmPalette.onPrimary,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(FmRadii.chip))
                .background(FmPalette.primary)
                .clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
        Spacer(Modifier.height(FmSpacing.sm))
        Text(
            text = secondary,
            color = FmPalette.onSurfaceMuted,
            fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onSecondary).padding(8.dp),
        )
    }
}
