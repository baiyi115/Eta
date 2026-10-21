package io.github.mangi.eta.agent.accessibility

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaControlDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceTheme
import io.github.mangi.eta.ui.components.MiuixDialogActions
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix content only; PhoneUseConfirmationHost owns the protected accessibility window. */
@Composable
internal fun PhoneUseConfirmationContent(
    message: String,
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var confirmEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(500L)
        confirmEnabled = true
    }
    EtaPreferenceTheme {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .background(MiuixTheme.colorScheme.windowDimming)
                .safeDrawingPadding()
                .padding(
                    horizontal = EtaControlDefaults.DialogOutsideMargin.width,
                    vertical = EtaControlDefaults.DialogOutsideMargin.height,
                ),
        ) {
            val centered = maxWidth >= 840.dp && maxHeight >= 480.dp
            val previewHeight = minOf(360.dp, maxHeight * 0.45f)
            Surface(
                modifier = Modifier
                    .align(if (centered) Alignment.Center else Alignment.BottomCenter)
                    .widthIn(max = DialogDefaults.MaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.85f),
                shape = RoundedCornerShape(DialogDefaults.CornerRadius),
                color = MiuixTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = EtaControlDefaults.DialogInsideMargin.width,
                        vertical = EtaControlDefaults.DialogInsideMargin.height,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.phone_use_confirmation_title),
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = DialogDefaults.titleColor(),
                    )
                    // Keep both actions visible even with long explanations or large fonts.
                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                        Image(
                            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                            contentDescription = stringResource(R.string.phone_use_confirmation_preview),
                            modifier = Modifier.fillMaxWidth().height(previewHeight)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MiuixTheme.colorScheme.background),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.phone_use_confirmation_allow),
                        cancelText = stringResource(R.string.phone_use_confirmation_cancel),
                        confirmEnabled = confirmEnabled,
                        onCancel = onCancel,
                        onConfirm = onConfirm,
                    )
                }
            }
        }
    }
}
