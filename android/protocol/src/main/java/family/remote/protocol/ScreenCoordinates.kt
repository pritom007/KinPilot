package family.remote.protocol

/** Coordinates inside a centered, aspect-fit video. Black bars are not remote pixels. */
object ScreenCoordinates {
    fun normalize(x: Float, y: Float, viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int): Pair<Float, Float>? {
        if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val scale = minOf(viewWidth.toFloat() / frameWidth, viewHeight.toFloat() / frameHeight)
        val width = frameWidth * scale
        val height = frameHeight * scale
        val nx = (x - (viewWidth - width) / 2f) / width
        val ny = (y - (viewHeight - height) / 2f) / height
        return if (nx.isFinite() && ny.isFinite() && nx in 0f..1f && ny in 0f..1f) nx to ny else null
    }
}
