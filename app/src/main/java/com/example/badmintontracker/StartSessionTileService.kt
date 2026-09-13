package com.example.badmintontracker

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipDefaults
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A single-purpose Wear OS tile: swipe to it from the watch face and tap
 * once to jump straight into a tracked session, instead of opening the app
 * and tapping "Start" yourself.
 *
 * Tapping the tile launches MainActivity with EXTRA_AUTO_START = true,
 * which MainActivity.handleAutoStartIntent() reads to start tracking right
 * away (see MainActivity.kt). MainActivity is launchMode="singleTask" (see
 * AndroidManifest.xml) so tapping the tile while the app is already open
 * reuses that instance via onNewIntent() rather than spawning a second one.
 *
 * Deliberately just one button and no live stats on the tile itself — a
 * tile that has to read SessionHistoryStore/SharedPreferences on every
 * onTileRequest to show "last session" data is a reasonable follow-up, but
 * it's not what this first version needs to do its one job.
 */
class StartSessionTileService : TileService() {

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> {
        val timeline = Timeline.fromLayoutElement(tileLayout(requestParams.deviceConfiguration))
        return Futures.immediateFuture(
            Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(timeline)
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> {
        // No images or fonts used by this tile — an empty, versioned resource
        // bundle is all the renderer needs.
        return Futures.immediateFuture(
            Resources.Builder().setVersion(RESOURCES_VERSION).build()
        )
    }

    private fun tileLayout(deviceParameters: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val launchMainActivity = Clickable.Builder()
            .setId(CLICKABLE_ID)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_AUTO_START,
                                ActionBuilders.booleanExtra(true)
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val startChip = Chip.Builder(this, launchMainActivity, deviceParameters)
            .setPrimaryLabelContent("Start session")
            .setChipColors(ChipDefaults.PRIMARY_COLORS)
            .build()

        // Center the single chip in the tile's square/round content area —
        // simpler and more predictable here than reaching for the opinionated
        // PrimaryLayout for a layout this small.
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(startChip)
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val CLICKABLE_ID = "start_session"
    }
}
