package com.example.feature.gallery

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.imageprocessing.PhotographicStyle
import com.example.core.imageprocessing.PhotographicStyleBitmapProcessor
import com.example.core.imageprocessing.PhotographicStyleCatalog
import com.example.core.imageprocessing.PortraitBokehEngine
import com.example.core.storage.CapturedMediaItem
import com.example.core.storage.MediaStoreRepository
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NothingBorder
import com.example.ui.theme.NothingGrey
import com.example.ui.theme.NothingObsidian
import com.example.ui.theme.NothingRed
import com.example.ui.theme.NothingSurface
import com.example.ui.theme.NothingSurfaceElevated
import com.example.ui.theme.NothingWhite
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
  mediaStoreRepository: MediaStoreRepository,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onNavigateBack)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  var mediaItems by remember { mutableStateOf<List<CapturedMediaItem>>(emptyList()) }
  var selectedItem by remember { mutableStateOf<CapturedMediaItem?>(null) }
  var isEditing by remember { mutableStateOf(false) }

  suspend fun reloadMedia(selectUri: Uri? = null) {
    val loaded = mediaStoreRepository.loadAppCapturedMedia(limit = 60)
    mediaItems = loaded
    selectedItem =
      if (selectUri != null) {
        loaded.firstOrNull { it.uri == selectUri } ?: loaded.firstOrNull()
      } else {
        loaded.firstOrNull()
      }
  }

  LaunchedEffect(Unit) { reloadMedia() }

  if (isEditing && selectedItem != null && !selectedItem!!.isVideo) {
    PostCaptureStyleEditor(
      item = selectedItem!!,
      mediaStoreRepository = mediaStoreRepository,
      onCancel = { isEditing = false },
      onSaved = { newUri ->
        isEditing = false
        scope.launch {
          reloadMedia(newUri)
          snackbarHostState.showSnackbar("Edited copy saved")
        }
      },
    )
    return
  }

  Scaffold(
    containerColor = NothingObsidian,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "CAPTURED MEDIA",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = NothingWhite,
          )
        },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("gallery_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to camera",
              tint = NothingWhite,
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NothingObsidian),
      )
    },
    modifier = modifier.fillMaxSize(),
  ) { innerPadding ->
    if (mediaItems.isEmpty()) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Filled.PhotoLibrary,
            contentDescription = null,
            tint = NothingGrey,
            modifier = Modifier.size(48.dp),
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = "NO CAPTURES YET",
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.titleMedium,
            color = NothingWhite,
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Photos and videos captured with Nothing Camera Pro will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = NothingGrey,
          )
        }
      }
    } else {
      Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // Active item preview
        val current = selectedItem ?: mediaItems.first()
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier.weight(1f)
              .fillMaxWidth()
              .padding(horizontal = 12.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(Color.Black),
        ) {
          AsyncImage(
            model = current.uri,
            contentDescription = current.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
          if (current.isVideo) {
            Surface(
              color = NothingRed,
              shape = CircleShape,
              modifier =
                Modifier.size(64.dp).clickable {
                  val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                      setDataAndType(current.uri, "video/mp4")
                      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                  runCatching { context.startActivity(intent) }
                },
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Filled.PlayArrow,
                  contentDescription = "Play video",
                  tint = NothingWhite,
                  modifier = Modifier.size(34.dp),
                )
              }
            }
          }
        }

        // Action Bar
        Row(
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
          if (!current.isVideo) {
            Surface(
              color = NothingSurfaceElevated,
              shape = RoundedCornerShape(999.dp),
              border = BorderStroke(1.dp, NothingBorder),
              modifier =
                Modifier.minimumInteractiveComponentSize()
                  .clickable { isEditing = true }
                  .testTag("gallery_edit_style_button"),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.AutoFixHigh,
                  contentDescription = "Edit Photographic Style or Bokeh",
                  tint = NothingWhite,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "EDIT STYLE / DEPTH",
                  fontFamily = JetBrainsMonoFontFamily,
                  fontSize = 11.sp,
                  color = NothingWhite,
                )
              }
            }
          }

          IconButton(
            onClick = {
              val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                  type = if (current.isVideo) "video/mp4" else "image/jpeg"
                  putExtra(Intent.EXTRA_STREAM, current.uri)
                  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
              runCatching { context.startActivity(Intent.createChooser(shareIntent, "Share media")) }
            },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("gallery_share_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Share,
              contentDescription = "Share media",
              tint = NothingWhite,
            )
          }

          IconButton(
            onClick = {
              scope.launch {
                val deleted = mediaStoreRepository.deleteMedia(current.uri)
                if (deleted) {
                  reloadMedia()
                  snackbarHostState.showSnackbar("Deleted")
                }
              }
            },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("gallery_delete_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Delete,
              contentDescription = "Delete media",
              tint = NothingRed,
            )
          }
        }

        // Filmstrip / Thumbnail Grid
        LazyVerticalGrid(
          columns = GridCells.Adaptive(76.dp),
          contentPadding = PaddingValues(12.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.height(176.dp).fillMaxWidth(),
        ) {
          items(mediaItems, key = { it.uri.toString() }) { item ->
            val isSelected = item.uri == current.uri
            Box(
              modifier =
                Modifier.aspectRatio(1f)
                  .clip(RoundedCornerShape(10.dp))
                  .background(NothingSurface)
                  .clickable { selectedItem = item },
            ) {
              AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
              if (item.isVideo) {
                Icon(
                  imageVector = Icons.Filled.PlayArrow,
                  contentDescription = "Video",
                  tint = NothingWhite,
                  modifier =
                    Modifier.align(Alignment.Center)
                      .size(22.dp)
                      .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                      .padding(2.dp),
                )
              }
              if (isSelected) {
                Box(
                  modifier =
                    Modifier.fillMaxSize()
                      .background(Color.Transparent)
                      .padding(2.dp),
                ) {
                  Box(
                    modifier =
                      Modifier.align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NothingRed),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostCaptureStyleEditor(
  item: CapturedMediaItem,
  mediaStoreRepository: MediaStoreRepository,
  onCancel: () -> Unit,
  onSaved: (Uri) -> Unit,
) {
  BackHandler(onBack = onCancel)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val bokehEngine = remember { PortraitBokehEngine() }

  var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var selectedStyle by remember { mutableStateOf<PhotographicStyle>(PhotographicStyleCatalog.Natural) }
  var styleIntensity by remember { mutableFloatStateOf(1.0f) }
  var blurStrength by remember { mutableFloatStateOf(0.0f) }
  var isSaving by remember { mutableStateOf(false) }

  LaunchedEffect(item.uri) {
    val sourceUri = item.originalUneditedUri ?: item.uri
    val loaded =
      withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(sourceUri)?.use { stream ->
          val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
          BitmapFactory.decodeStream(stream, null, opts)
        }
      }
    baseBitmap = loaded
    previewBitmap = loaded
  }

  LaunchedEffect(baseBitmap, selectedStyle, styleIntensity, blurStrength) {
    val src = baseBitmap ?: return@LaunchedEffect
    val rendered =
      withContext(Dispatchers.Default) {
        val afterBokeh =
          if (blurStrength > 0.02f) {
            bokehEngine.applyPortraitBokeh(src, blurStrength).first
          } else {
            src
          }
        PhotographicStyleBitmapProcessor.applyStyle(afterBokeh, selectedStyle, styleIntensity)
      }
    previewBitmap = rendered
  }

  Scaffold(
    containerColor = NothingObsidian,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = "POST-CAPTURE EDITOR",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = NothingWhite,
          )
        },
        navigationIcon = {
          IconButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Cancel editing",
              tint = NothingWhite,
            )
          }
        },
        actions = {
          Button(
            enabled = !isSaving && baseBitmap != null,
            onClick = {
              scope.launch {
                isSaving = true
                val sourceUri = item.originalUneditedUri ?: item.uri
                val fullRes =
                  withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                      BitmapFactory.decodeStream(stream)
                    }
                  }
                if (fullRes != null) {
                  val afterBokeh =
                    if (blurStrength > 0.02f) {
                      bokehEngine.applyPortraitBokeh(fullRes, blurStrength).first
                    } else {
                      fullRes
                    }
                  val styled =
                    withContext(Dispatchers.Default) {
                      PhotographicStyleBitmapProcessor.applyStyle(
                        afterBokeh,
                        selectedStyle,
                        styleIntensity,
                      )
                    }
                  val saved =
                    mediaStoreRepository.saveBitmapToMediaStore(styled, prefix = "EDIT", quality = 95)
                  isSaving = false
                  if (saved != null) onSaved(saved)
                } else {
                  isSaving = false
                }
              }
            },
            colors = ButtonDefaults.buttonColors(containerColor = NothingRed, contentColor = NothingWhite),
            modifier = Modifier.padding(end = 8.dp),
          ) {
            if (isSaving) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NothingWhite)
            } else {
              Icon(imageVector = Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("SAVE COPY", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp)
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NothingObsidian),
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(12.dp)) {
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
      ) {
        val bmp = previewBitmap
        if (bmp != null) {
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Edited preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          CircularProgressIndicator(color = NothingWhite)
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(PhotographicStyleCatalog.allStyles, key = { it.id }) { style ->
          val active = style.id == selectedStyle.id
          Surface(
            color = if (active) NothingWhite else NothingSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (active) NothingWhite else NothingBorder),
            modifier = Modifier.minimumInteractiveComponentSize().clickable { selectedStyle = style },
          ) {
            Text(
              text = style.name,
              fontFamily = JetBrainsMonoFontFamily,
              fontSize = 11.sp,
              color = if (active) NothingObsidian else NothingWhite,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("STYLE", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = NothingGrey)
        Spacer(modifier = Modifier.width(12.dp))
        Slider(
          value = styleIntensity,
          onValueChange = { styleIntensity = it },
          colors = SliderDefaults.colors(thumbColor = NothingWhite, activeTrackColor = NothingWhite),
          modifier = Modifier.weight(1f),
        )
        Text(
          "${(styleIntensity * 100).roundToInt()}%",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          color = NothingWhite,
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("BOKEH", fontFamily = JetBrainsMonoFontFamily, fontSize = 11.sp, color = NothingGrey)
        Spacer(modifier = Modifier.width(12.dp))
        Slider(
          value = blurStrength,
          onValueChange = { blurStrength = it },
          colors = SliderDefaults.colors(thumbColor = NothingRed, activeTrackColor = NothingRed),
          modifier = Modifier.weight(1f),
        )
        Text(
          "${(blurStrength * 100).roundToInt()}%",
          fontFamily = JetBrainsMonoFontFamily,
          fontSize = 11.sp,
          color = NothingWhite,
        )
      }
    }
  }
}
