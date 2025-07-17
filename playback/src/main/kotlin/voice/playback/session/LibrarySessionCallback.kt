package voice.playback.session

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import voice.common.BookId
import voice.common.pref.CurrentBookStore
import voice.data.Book
import voice.data.repo.BookRepository
import voice.logging.core.Logger
import voice.playback.player.VoicePlayer
import voice.playback.session.search.BookSearchHandler
import voice.playback.session.search.BookSearchParser
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class LibrarySessionCallback
@Inject constructor(
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
  private val player: VoicePlayer,
  private val bookSearchParser: BookSearchParser,
  private val bookSearchHandler: BookSearchHandler,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val bookRepository: BookRepository,
) : MediaLibrarySession.Callback {

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    mediaItems: MutableList<MediaItem>,
  ): ListenableFuture<List<MediaItem>> {
    Logger.d("onAddMediaItems")
    return scope.future {
      mediaItems.map { item ->
        mediaItemProvider.item(item.mediaId) ?: item
      }
    }
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: ControllerInfo,
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    Logger.d("onSetMediaItems(mediaItems.size=${mediaItems.size}, startIndex=$startIndex, startPosition=$startPositionMs)")
    val item = mediaItems.singleOrNull()
    return if (startIndex == C.INDEX_UNSET && startPositionMs == C.TIME_UNSET && item != null) {
      scope.future {
        onSetMediaItemsForSingleItem(item)
          ?: super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs).await()
      }
    } else {
      super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
    }
  }

  private suspend fun onSetMediaItemsForSingleItem(item: MediaItem): MediaItemsWithStartPosition? {
    val searchQuery = item.requestMetadata.searchQuery
    return if (searchQuery != null) {
      val search = bookSearchParser.parse(searchQuery, item.requestMetadata.extras)
      val searchResult = bookSearchHandler.handle(search) ?: return null
      currentBookStoreId.updateData { searchResult.id }
      mediaItemProvider.mediaItemsWithStartPosition(searchResult)
    } else {
      (item.mediaId.toMediaIdOrNull() as? MediaId.Book)?.let { bookId ->
        currentBookStoreId.updateData { bookId.id }
      }
      mediaItemProvider.mediaItemsWithStartPosition(item.mediaId)
    }
  }

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val mediaItem = if (params?.isRecent == true) {
      mediaItemProvider.recent() ?: mediaItemProvider.root()
    } else {
      mediaItemProvider.root()
    }
    Logger.d("onGetLibraryRoot(isRecent=${params?.isRecent == true}). Returning ${mediaItem.mediaId}")
    return Futures.immediateFuture(LibraryResult.ofItem(mediaItem, params))
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
    Logger.d("onGetItem(mediaId=$mediaId)")
    val item = mediaItemProvider.item(mediaId)
    if (item != null) {
      LibraryResult.ofItem(item, null)
    } else {
      LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
    Logger.d("onGetChildren for $parentId")
    val children = mediaItemProvider.children(parentId)
    if (children != null) {
      LibraryResult.ofItemList(children, params)
    } else {
      LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }
  }

  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: ControllerInfo,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    Logger.d("onPlaybackResumption")
    return scope.future {
      val currentBook = currentBook()
      if (currentBook != null) {
        mediaItemProvider.mediaItemsWithStartPosition(currentBook)
      } else {
        throw UnsupportedOperationException()
      }
    }
  }

  private suspend fun currentBook(): Book? {
    val bookId = currentBookStoreId.data.first() ?: return null
    return bookRepository.get(bookId)
  }

  override fun onConnect(
    session: MediaSession,
    controller: ControllerInfo,
  ): ConnectionResult {
    Logger.d("onConnect to ${controller.packageName}")

    if (player.playbackState == Player.STATE_IDLE &&
      controller.packageName == "com.google.android.projection.gearhead"
    ) {
      Logger.d("onConnect to ${controller.packageName} and player is idle.")
      Logger.d("Preparing current book so it shows up as recently played")
      scope.launch {
        prepareCurrentBook()
      }
    }

    val connectionResult = super.onConnect(session, controller)
    val sessionCommands = connectionResult.availableSessionCommands
      .buildUpon()
      .add(SessionCommand(CustomCommand.CUSTOM_COMMAND_ACTION, Bundle.EMPTY))
      .build()
    return ConnectionResult.accept(
      sessionCommands,
      connectionResult.availablePlayerCommands,
    )
  }

  private suspend fun prepareCurrentBook() {
    val bookId = currentBookStoreId.data.first() ?: return
    val book = bookRepository.get(bookId) ?: return
    val item = mediaItemProvider.mediaItem(book)
    player.setMediaItem(item)
    player.prepare()
  }

  override fun onCustomCommand(
    session: MediaSession,
    controller: ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle,
  ): ListenableFuture<SessionResult> {
    val command = CustomCommand.parse(customCommand, args)
      ?: return super.onCustomCommand(session, controller, customCommand, args)
    when (command) {
      CustomCommand.ForceSeekToNext -> {
        player.forceSeekToNext()
      }

      CustomCommand.ForceSeekToPrevious -> {
        player.forceSeekToPrevious()
      }
      CustomCommand.FastForward -> {
        player.fastForward()
      }
      CustomCommand.Rewind -> {
        player.rewind()
      }

      is CustomCommand.SetSkipSilence -> {
        player.setSkipSilenceEnabled(command.skipSilence)
      }
      is CustomCommand.SetGain -> {
        player.setGain(command.gain)
      }
    }

    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
  }

  override fun onMediaButtonEvent(session: MediaSession, controllerInfo: ControllerInfo, intent: Intent): Boolean {
    if (Intent.ACTION_MEDIA_BUTTON != intent.action) {
      return false;
    }
    val keyEvent = if (Build.VERSION.SDK_INT >= 33) {
      intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
    if (keyEvent == null) {
      return false
    }

    log("keyEvent dump: ${keyEventToString(keyEvent)}")
    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
      return debounceKeyEvent(keyEvent)
    }
    return true
  }


  // on some devices / Android versions long press events are being "collected" for a timespan of 1000ms,
  // so holding the button down does not fire any events for at least 1000ms. Therefore, after releasing
  // you have to wait at least 1050ms to ensure no long press is being performed
  // some devices have shorter delays

  // 650 for unihertz jelly 2e
  val shortDelay = 650.milliseconds

  // 1100 for Pixel 4a
  val longerDelay = 1100.milliseconds

  // repeated events on buttonHold are sent with about 50ms delay
  // to detect a holdEnded, the delay has to be slightly higher than this delay
  val holdEndedDelay = 100.milliseconds

  var clickCount = 0

  // state store before handling the action - wasPlaying=true means that the player was not paused
  var wasPlaying = false
  // job that performs an action after buttonHold has ended
  var buttonHoldEndedJob: Job? = null

  // job that gets executed after button has been released without buttonHold
  var buttonReleasedJob: Job? = null

  private fun log(message:String) {
    Log.d(
      "onMediaButtonEvent",
      "==== $message"
    )
  }
  private fun keyEventToString(keyEvent: KeyEvent): String {
    val action = when (keyEvent.action) {
      KeyEvent.ACTION_UP -> "ACTION_UP"
      KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
      else -> "ACTION_UNKNOWN"
    }
    val keyCode = when (keyEvent.keyCode) {
      KeyEvent.KEYCODE_HEADSETHOOK -> "KEYCODE_HEADSETHOOK"
      KeyEvent.KEYCODE_MEDIA_PLAY -> "KEYCODE_MEDIA_PLAY"
      KeyEvent.KEYCODE_MEDIA_PAUSE -> "KEYCODE_MEDIA_PAUSE"
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
      KeyEvent.KEYCODE_MEDIA_NEXT -> "KEYCODE_MEDIA_NEXT"
      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "KEYCODE_MEDIA_PREVIOUS"
      KeyEvent.KEYCODE_MEDIA_STOP -> "KEYCODE_MEDIA_STOP"
      else -> "KEYCODE_UNKNOWN"
    }
    return "keyCode=$keyCode, action=$action, repeatCount=${keyEvent.repeatCount}"
  }

  private fun debounceKeyEvent(keyEvent: KeyEvent): Boolean {
    log("debounceKeyEvent: ${keyEventToString(keyEvent)}, clickCount=$clickCount, repeatCount=${keyEvent.repeatCount}")

    // this is device dependant
    // - longerDelay is less responsive but secure for all devices
    // - shortDelay is what we want, but most devices just don't support it

    val timerDelay = longerDelay
    val clickPressed = keyEvent.repeatCount > 0

    // only increase the clickCount on non-clickPressed events
    if(!clickPressed) {
      when (keyEvent.keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
          clickCount++
          log("handleCallMediaButton: Headset Hook/Play/ Pause, clickCount=$clickCount")
        }

        KeyEvent.KEYCODE_MEDIA_NEXT -> {
          clickCount += 2
          log("handleCallMediaButton: Media Next, clickCount=$clickCount")
        }

        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
          clickCount += 3
          log("handleCallMediaButton: Media Previous, clickCount=$clickCount")
        }

        KeyEvent.KEYCODE_MEDIA_STOP -> {
          log("handleCallMediaButton: Media Stop, clickCount=$clickCount")
          player.stop()
          buttonReleasedJob?.cancel()
          clickCount = 0
          return true
        }

        else -> {
          log("Unhandled KeyEvent: ${keyEvent.keyCode}, clickCount=$clickCount")
          return false
        }
      }
    }

    // cancel all running jobs on a new click
    buttonReleasedJob?.cancel()
    buttonHoldEndedJob?.cancel()

    if(clickPressed) {
      // only handle first repeatedEvent
      if(keyEvent.repeatCount < 3) {
        wasPlaying = player.isPlaying
        handleClickPressed(clickCount)
      }
      buttonHoldEndedJob = scope.launch {
        log("clickPressedJob: scheduled")
        delay(holdEndedDelay)
        log("clickPressedJob: execute")
        clickCount = 0
        if(wasPlaying) {
          log("playerAction - holdEnded: play")
          player.play()
        } else {
          log("playerAction - holdEnded: pause")
          player.pause()
        }
      }
    } else {
      wasPlaying = player.isPlaying
      buttonReleasedJob = scope.launch {
        // delay(650);
        log("clickReleasedJob scheduled: delay=${timerDelay.inWholeMilliseconds}ms, clicks=$clickCount, hold=$clickPressed ==== ${
            keyEventToString(keyEvent)
          }"
        )
        delay(timerDelay)
        log("clickReleasedJob executed: delay=${timerDelay.inWholeMilliseconds}ms, clicks=$clickCount, hold=$clickPressed ==== ${
          keyEventToString(keyEvent)
        }")
        handleClicksReleased(clickCount)
        clickCount = 0
      }
    }

    return true
  }

  private fun handleClickPressed(clickCount: Int) {

    log("handleClickPressed: count=$clickCount")
    when(clickCount) {
      0 -> {
        log("playerAction - clickPressed: seekback")
        player.stepBack()
      }
      1 -> {
        log("playerAction - clickPressed: fastForward")
        player.fastForward()
      }
      2 -> {
        log("playerAction - clickPressed: rewind")
        player.rewind()
      }
    }
  }

  fun handleClicksReleased(clickCount: Int) {
    log("handleClickReleased: count=$clickCount")

      when (clickCount) {
        1 -> {
          if(player.isSeeking) {
            log("playerAction - clickReleased: seeking restoreStatus")
            if(wasPlaying) {
              player.play()
            } else {
              player.pause()
            }
          } else if (player.isPlaying) {
            log("playerAction - clickReleased: pause")
            player.pause()
          } else {
            log("playerAction - clickReleased: play")
            player.play()
          }
        }

        2 -> {
          log("playerAction - clickReleased: next")
          // player.forceSeekToNext() // this will seek to next chapter
          player.seekForward(5.minutes)
        }

        3 -> {
          log("playerAction - clickReleased: previous")
          // player.forceSeekToPrevious()
          player.seekBack(5.minutes)
        }
        4 -> {
          log("playerAction - clickReleased: stepBack")
          player.stepBack()
        }
        5 -> {
          log("playerAction - clickReleased: rewind")
          player.rewind()
        }
    }


  }
}
