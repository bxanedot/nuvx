// SPDX-License-Identifier: AGPL-3.0-or-later
/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.bxane.nuvx.playback.smart

object MelSpectrogram {

    val available: Boolean get() = NativeAnalysisLibrary.available

    val mels: Int by lazy { if (available) nativeMelCount() else 128 }

    val sampleRate: Double by lazy { if (available) nativeSampleRate() else 22_050.0 }

    val hop: Int by lazy { if (available) nativeHop() else 441 }

    val frameRate: Double get() = sampleRate / hop

    fun compute(samples: FloatArray, sampleRate: Double = this.sampleRate): Spectrogram? {
        if (!available || samples.isEmpty()) return null
        val values = nativeCompute(samples, sampleRate)
        if (values.isEmpty()) return null
        return Spectrogram(values = values, frames = values.size / mels, mels = mels)
    }

    data class Spectrogram(val values: FloatArray, val frames: Int, val mels: Int) {
        val durationSeconds: Double get() = frames / (sampleRate / hop)

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Spectrogram && frames == other.frames && mels == other.mels &&
                    values.contentEquals(other.values))

        override fun hashCode(): Int = 31 * (31 * values.contentHashCode() + frames) + mels
    }

    fun resample(samples: FloatArray, inputRate: Double, outputRate: Double = sampleRate): FloatArray? {
        if (!available || samples.isEmpty() || inputRate <= 0 || outputRate <= 0) return null
        return nativeResample(samples, inputRate, outputRate).takeIf { it.isNotEmpty() }
    }

    @JvmStatic private external fun nativeCompute(samples: FloatArray, sampleRate: Double): FloatArray
    @JvmStatic private external fun nativeResample(
        samples: FloatArray,
        inputRate: Double,
        outputRate: Double,
    ): FloatArray
    @JvmStatic private external fun nativeMelCount(): Int
    @JvmStatic private external fun nativeSampleRate(): Double
    @JvmStatic private external fun nativeHop(): Int
}
