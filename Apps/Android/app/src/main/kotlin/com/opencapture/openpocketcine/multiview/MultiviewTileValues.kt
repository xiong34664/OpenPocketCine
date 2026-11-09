package com.opencapture.openpocketcine.multiview

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opencapture.openpocketcine.R
import com.opencapture.opencine.appkit.MultiviewCameraAction
import com.opencapture.opencine.appkit.MultiviewReadout
import com.opencapture.opencine.appkit.MultiviewRecordContext
import com.opencapture.opencine.appkit.MultiviewTile
import com.opencapture.opencine.appkit.MultiviewTileStatus
import com.opencapture.opencine.appkit.withPower
import com.opencapture.opencine.dji.hasMultiviewPreview
import com.opencapture.opencine.core.PowerStatus
import com.opencapture.opencine.cameracontrols.cameraBatteryReadoutLabel
import com.opencapture.opencine.vocabulary.CameraCommands
import com.opencapture.opencine.vocabulary.CameraStatus
import com.opencapture.opencine.vocabulary.StorageReadout
import com.opencapture.opencine.vocabulary.storageReadout
import com.opencapture.opencine.media.storageReadout
import com.opencapture.opencine.core.StorageStatus
import com.opencapture.opencine.vocabulary.VideoResolution

/** Display values come from the existing throttled tile status; no telemetry polling. */
internal data class MultiviewTileReadouts(
    val letter: String,
    val name: String,
    val model: String,
    val timecode: String,
    val battery: String,
    val storage: String,
    val recording: String,
    val isRecording: Boolean,
    val format: String,
    val recovery: String?,
)

@Composable
internal fun multiviewTileReadouts(
    index: Int, name: String, model: String, settings: CameraStatus, timecode: String?,
    recordingObservation: Boolean?, recordingAvailable: Boolean, recordingBusy: Boolean,
    recovering: Boolean, hasPicture: Boolean, failure: String?, recordingNote: String? = null,
    power: PowerStatus,
    colorFamily: String = "pocket",
    storage: StorageStatus? = null,
): MultiviewTileReadouts {
    val elapsed = settings.recordElapsedSec.coerceAtLeast(0)
    val recording = when {
        recordingBusy -> stringResource(R.string.multiview_tally_wait)
        recordingNote?.contains("rejected") == true || recordingNote?.contains("No confirmation") == true -> stringResource(R.string.multiview_tally_rec_q)
        recovering -> stringResource(R.string.multiview_tally_hold)
        !recordingAvailable || recordingObservation == null -> stringResource(R.string.multiview_tally_rec_dash)
        recordingObservation -> stringResource(R.string.multiview_tally_rec_time, elapsed / 60, elapsed % 60)
        else -> stringResource(R.string.multiview_tally_stby)
    }
    val resolution = VideoResolution.fromRaw(settings.resolutionCode)?.label ?: "—"
    val fps = if (settings.fps > 0) "${settings.fps}p" else "—"
    val color = if (settings.colorMode < 0) "—" else CameraCommands.colorLabel(settings.colorMode, colorFamily)
    return MultiviewTileReadouts(
        letter = ('A' + index.coerceIn(0, 3)).toString(), name = name, model = model,
        timecode = timecode ?: "\u2014",
        battery = cameraBatteryReadoutLabel(power.batteryReading) ?: "\u2014",
        storage = (if (storage != null) storage.storageReadout else settings.storageReadout)?.free ?: StorageReadout.UNAVAILABLE,
        recording = recording, isRecording = recordingObservation == true,
        format = "$resolution · $fps · $color",
        recovery = when {
            failure != null -> stringResource(R.string.multiview_connection_attention)
            recovering -> stringResource(R.string.multiview_restoring_picture)
            !hasPicture -> stringResource(R.string.multiview_waiting_picture)
            else -> null
        },
    )
}

@Composable
internal fun multiviewExposureReadouts(settings: CameraStatus): List<Pair<String, String>> = listOf(
    stringResource(R.string.multiview_readout_iso) to if (settings.iso > 0) settings.iso.toString() else "—",
    stringResource(R.string.multiview_readout_shutter) to if (settings.shutterDenom > 0) "1/${settings.shutterDenom}" else "—",
    stringResource(R.string.multiview_readout_wb) to when {
        settings.wbMode == CameraCommands.WB_AUTO -> stringResource(R.string.multiview_readout_auto)
        settings.wbMode < 0 -> "—"
        settings.wbKelvin > 0 -> "${settings.wbKelvin}K"
        else -> "—"
    },
    stringResource(R.string.multiview_readout_focus) to settings.focusLabel,
)

/** The program readouts and Grid's in-tile row. */
@Composable
internal fun multiviewStageReadouts(settings: CameraStatus): List<MultiviewReadout> =
    multiviewExposureReadouts(settings).map { (title, value) -> MultiviewReadout(title, value) }

/** Pocket's camera option ids beside the shared Live View id. */
internal object MultiviewTileAction {
    const val RECORD = "record"
    const val LUT = "lut"
    const val RECONNECT = "reconnect"
    const val EXPERIMENTAL = "experimental"
    const val REMOVE = "remove"
}

/** The shared stage's values for one slot, null while it is empty. */
@Composable
internal fun MultiviewSession.stageTile(index: Int): MultiviewTile? {
    val tile = tiles[index]
    val camera = tile.camera ?: return null
    val power = tile.control.current.power
    val readouts = multiviewTileReadouts(
        index, camera.name, camera.model.name, tile.settings, tile.timecodeReadout,
        tile.recordingObservation?.first, tile.recordingAvailable,
        tile.recordingBusy, tile.recovering, tile.hasPicture, tile.failureMessage, tile.recordingNote,
        power = power, colorFamily = camera.model.family, storage = tile.control.snapshots.value.storage,
    )
    val settled = !busy && !tile.connecting && !tile.recovering
    val experimental = tile.failureMessage != null && !tile.experimentalNetwork && tile.identity == null
    val record = recordPlan(tile)
    return MultiviewTile(
        name = camera.name, model = camera.model.name, format = readouts.format,
        storage = readouts.storage, timecode = tile.timecodeReadout,
        tally = readouts.recording, recording = readouts.isRecording, lut = tile.lutEnabled,
        readouts = multiviewStageReadouts(tile.settings),
        status = readouts.recovery?.let { text ->
            MultiviewTileStatus(
                text, detail = tile.failureMessage ?: tile.status,
                inProgress = tile.failureMessage == null && !tile.networkVerified,
                actions = if (tile.failureMessage == null) emptyList() else listOfNotNull(
                    MultiviewCameraAction(MultiviewTileAction.EXPERIMENTAL, stringResource(R.string.multiview_try_experimental), enabled = settled)
                        .takeIf { experimental },
                    MultiviewCameraAction(MultiviewTileAction.RECONNECT, stringResource(R.string.multiview_reconnect), enabled = settled),
                    MultiviewCameraAction(
                        MultiviewTileAction.REMOVE, stringResource(R.string.multiview_remove), MultiviewCameraAction.Kind.DESTRUCTIVE, enabled = settled,
                    ),
                ),
            )
        },
        failure = tile.failureMessage,
        footnote = stringResource(R.string.multiview_remove_tile_note)
            .takeIf { tile.networkVerified && !camera.hasMultiviewPreview },
        actions = listOfNotNull(
            MultiviewCameraAction(
                MultiviewCameraAction.LIVE_VIEW, stringResource(R.string.multiview_open_live_view), enabled = tile.controlHost != null && !tile.connecting,
            ),
            MultiviewCameraAction(
                MultiviewTileAction.RECORD,
                if (tile.recordingObservation?.first == true) stringResource(R.string.multiview_stop_recording) else stringResource(R.string.multiview_start_recording),
                MultiviewCameraAction.Kind.RECORD,
                enabled = record.isExecutable && !groupRecordingBusy && !closing,
            ),
            MultiviewCameraAction(MultiviewTileAction.LUT, if (tile.lutEnabled) stringResource(R.string.multiview_disable_auto_lut) else stringResource(R.string.multiview_enable_auto_lut)),
            MultiviewCameraAction(MultiviewTileAction.RECONNECT, stringResource(R.string.multiview_reconnect), enabled = settled),
            MultiviewCameraAction(MultiviewTileAction.EXPERIMENTAL, stringResource(R.string.multiview_try_experimental), enabled = settled)
                .takeIf { experimental },
            MultiviewCameraAction(
                MultiviewTileAction.REMOVE, stringResource(R.string.multiview_remove_camera), MultiviewCameraAction.Kind.DESTRUCTIVE,
                enabled = !busy && !tile.connecting && !groupRecordingBusy && !tile.recordingBusy && !closing,
            ),
        ),
    ).withPower(power)
}

/** The record plan for every camera (null) or one slot, with what blocks it now. */
internal fun MultiviewSession.recordContext(slot: Int?): MultiviewRecordContext = MultiviewRecordContext(
    recordPlan(slot?.let { tiles[it] }), busy = busy || groupRecordingBusy, closing = closing,
)
