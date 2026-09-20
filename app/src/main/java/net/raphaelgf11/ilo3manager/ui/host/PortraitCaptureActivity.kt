package net.raphaelgf11.ilo3manager.ui.host

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The scanner in portrait.
 *
 * ZXing's own CaptureActivity is declared landscape in the library's manifest, so scanning rotated
 * the whole app sideways — and, because rotating recreates the activity, it also discarded the
 * host form the user was in the middle of filling in. Subclassing lets the app declare its own
 * orientation (see AndroidManifest.xml) without forking the library.
 */
class PortraitCaptureActivity : CaptureActivity()
