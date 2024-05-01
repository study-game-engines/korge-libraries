package korlibs.time

import korlibs.time.core.*
import kotlin.time.*

// Required to have a reasonable performance on JS
inline class FastDuration(private val ms: Double) : Comparable<FastDuration> {
    companion object {
        val ZERO get() = FastDuration(0.0)
        val POSITIVE_INFINITY get() = FastDuration(Double.POSITIVE_INFINITY)
        val NEGATIVE_INFINITY get() = FastDuration(Double.NEGATIVE_INFINITY)
        val NaN get() = FastDuration(Double.NaN)

        @OptIn(CoreTimeInternalApi::class)
        fun now(): FastDuration = CoreTime.currentTimeMillisDouble().fastMilliseconds
    }
    val seconds: Double get() = ms / 1_000
    val milliseconds: Double get() = ms
    val microseconds: Double get() = ms * 1_000
    val nanoseconds: Double get() = ms * 1_000_000

    operator fun unaryPlus(): FastDuration = this
    operator fun unaryMinus(): FastDuration = FastDuration(-ms)
    operator fun plus(that: FastDuration): FastDuration = FastDuration(this.ms + that.ms)
    operator fun minus(that: FastDuration): FastDuration = FastDuration(this.ms - that.ms)
    operator fun div(other: FastDuration): Double = this.ms / other.ms

    operator fun times(scale: Double): FastDuration = FastDuration(this.ms * scale)
    operator fun div(scale: Double): FastDuration = FastDuration(this.ms / scale)
    inline operator fun times(scale: Number): FastDuration = times(scale.toDouble())
    inline operator fun div(scale: Number): FastDuration = div(scale.toDouble())

    operator fun plus(other: Duration): FastDuration = FastDuration(this.milliseconds + other.milliseconds)
    operator fun minus(other: Duration): FastDuration = FastDuration(this.milliseconds - other.milliseconds)
    operator fun times(other: Duration): FastDuration = FastDuration(this.milliseconds - other.milliseconds)
    operator fun div(other: Duration): Double = this.milliseconds / other.milliseconds

    // Comparable
    override fun compareTo(other: FastDuration): Int = this.ms.compareTo(other.ms)
    operator fun compareTo(other: Duration): Int = this.ms.compareTo(other.milliseconds)
}

operator fun Duration.compareTo(other: FastDuration): Int = this.milliseconds.compareTo(other.milliseconds)
operator fun Duration.plus(other: FastDuration): FastDuration = FastDuration(this.milliseconds + other.milliseconds)
operator fun Duration.minus(other: FastDuration): FastDuration = FastDuration(this.milliseconds - other.milliseconds)
operator fun Duration.times(other: FastDuration): FastDuration = FastDuration(this.milliseconds - other.milliseconds)
operator fun Duration.div(other: FastDuration): Double = this.milliseconds / other.milliseconds

val Duration.fast: FastDuration get() = this.milliseconds.fastMilliseconds
fun Duration.toFastDuration(): FastDuration = this.seconds.fastSeconds

val FastDuration.slow: Duration get() = this.milliseconds.milliseconds
fun FastDuration.toDuration(): Duration = this.seconds.seconds

val Double.fastSeconds: FastDuration get() = FastDuration(this * 1000)
val Double.fastMilliseconds: FastDuration get() = FastDuration(this)

val Int.fastSeconds: FastDuration get() = this.toDouble().fastSeconds
val Int.fastMilliseconds: FastDuration get() = this.toDouble().fastMilliseconds

val Float.fastSeconds: FastDuration get() = this.toDouble().fastSeconds
val Float.fastMilliseconds: FastDuration get() = this.toDouble().fastMilliseconds
