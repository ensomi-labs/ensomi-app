package io.ensomi.android.settings

import android.content.res.Resources
import io.ensomi.android.R

/** Generic output names follow the current language; product names remain the device's own. */
fun OutputRoute.localizedName(resources: Resources): String {
    val device = OutputRoutes.device(key) ?: return name
    val kind = OutputRoutes.kind(device.type)
    val product = device.productName.trim()
    if (product.isNotEmpty() && kind in setOf(OutputKind.BLUETOOTH, OutputKind.HEARING_AID, OutputKind.USB)) return product
    return resources.getString(when (kind) {
        OutputKind.SPEAKER -> R.string.output_speaker
        OutputKind.WIRED -> R.string.output_wired
        OutputKind.USB -> R.string.output_usb
        OutputKind.BLUETOOTH -> R.string.output_bluetooth
        OutputKind.HEARING_AID -> R.string.output_hearing_aid
        OutputKind.HDMI -> R.string.output_hdmi
        OutputKind.OTHER -> R.string.output_other
    })
}
