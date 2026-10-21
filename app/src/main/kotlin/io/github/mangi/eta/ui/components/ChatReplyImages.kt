package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Reply attachments use the same durable data-URL representation as user attachments. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatReplyImages(images: List<String>) {
    var selected by remember(images) { mutableStateOf<String?>(null) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        images.forEachIndexed { index, reference ->
            val image = rememberReplyBitmap(reference, 512)
            val bitmap = image.bitmap
            val label = stringResource(R.string.chat_reply_screenshot, index + 1)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(132.dp, 188.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button, onClickLabel = label) { selected = reference },
                ) {
                    if (bitmap != null) Image(
                        bitmap = bitmap, contentDescription = label,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    ) else Text(stringResource(if (image.loading) R.string.chat_reply_image_loading else R.string.chat_reply_image_failed), modifier = Modifier.padding(8.dp))
                }
                Text(label, style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    selected?.let { reference -> ReplyImagePreview(reference) { selected = null } }
}

/** Decode off the main thread; thumbnails never allocate full screenshot bitmaps. */
private data class ReplyBitmapState(val loading: Boolean = true, val bitmap: ImageBitmap? = null)

@Composable
private fun rememberReplyBitmap(reference: String, maxSide: Int): ReplyBitmapState {
    val image by produceState(ReplyBitmapState(), reference, maxSide) {
        value = ReplyBitmapState()
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                require(reference.startsWith("data:image/"))
                val bytes = Base64.decode(reference.substringAfter("base64,"), Base64.NO_WRAP)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > maxSide) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
        value = ReplyBitmapState(loading = false, bitmap = bitmap)
    }
    return image
}

@Composable
private fun ReplyImagePreview(reference: String, onDismiss: () -> Unit) {
    val image = rememberReplyBitmap(reference, 4096)
    val bitmap = image.bitmap
    var scale by remember(reference) { mutableStateOf(1f) }
    var offset by remember(reference) { mutableStateOf(Offset.Zero) }
    fun boundedOffset(candidate: Offset, zoom: Float, viewport: IntSize): Offset {
        val current = bitmap ?: return Offset.Zero
        if (viewport.width <= 0 || viewport.height <= 0 || zoom <= 1f) return Offset.Zero
        val fit = minOf(viewport.width.toFloat() / current.width, viewport.height.toFloat() / current.height)
        val limitX = ((current.width * fit * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val limitY = ((current.height * fit * zoom - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-limitX, limitX), candidate.y.coerceIn(-limitY, limitY))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.chat_reply_image_zoom), color = Color.White, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.action_close), color = Color.White,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss).padding(12.dp))
            }
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(0.dp))
                    .pointerInput(reference, bitmap) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 6f)
                            val anchor = centroid - Offset(size.width / 2f, size.height / 2f)
                            val nextOffset = anchor + (offset - anchor) * (nextScale / scale) + pan
                            offset = boundedOffset(nextOffset, nextScale, size)
                            scale = nextScale
                        }
                    }
                    .pointerInput(reference, bitmap) {
                        detectTapGestures(onDoubleTap = { position ->
                            val nextScale = if (scale > 1f) 1f else 2f
                            val anchor = position - Offset(size.width / 2f, size.height / 2f)
                            offset = boundedOffset(anchor * (1f - nextScale), nextScale, size)
                            scale = nextScale
                        })
                    }, contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) Image(
                    bitmap = bitmap, contentDescription = stringResource(R.string.chat_image_attachment),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
                ) else Text(stringResource(if (image.loading) R.string.chat_reply_image_loading else R.string.chat_reply_image_failed), color = Color.White)
            }
        }
    }
}
