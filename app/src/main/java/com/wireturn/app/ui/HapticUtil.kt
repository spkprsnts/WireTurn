package com.wireturn.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Использование системных тактильных откликов Android.
 * Оптимизировано под современные устройства (Pixel 9+) через VibrationEffect.Composition.
 */
object HapticUtil {

    enum class Pattern {
        /** Мягкий тик — выбор в меню, легкое касание */
        SELECTION,
        /** Стандартный клик — нажатие кнопки */
        CLICK,
        /** Включение — четкий двойной импульс */
        TOGGLE_ON,
        /** Выключение — одиночный мягкий отклик */
        TOGGLE_OFF,
        /** Успех — приятная восходящая последовательность */
        SUCCESS,
        /** Ошибка — тяжелый отталкивающий дребезг */
        ERROR,
        /** Старт приложения — приветственный паттерн */
        LAUNCH,
    }

    fun perform(context: Context, pattern: Pattern) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(VibratorManager::class.java)
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return

        val effect = when (pattern) {
            Pattern.SELECTION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                @Suppress("DEPRECATION")
                VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
            }

            Pattern.CLICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                @Suppress("DEPRECATION")
                VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
            }

            Pattern.TOGGLE_ON -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, 40)
                    .compose()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    @Suppress("DEPRECATION")
                    VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }

            Pattern.TOGGLE_OFF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                @Suppress("DEPRECATION")
                VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
            }

            Pattern.SUCCESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 60)
                    .compose()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                } else {
                    VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 20), -1)
                }
            }

            Pattern.ERROR -> when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f, 120)
                        .compose()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 120)
                        .compose()
                }
                else -> VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
            }

            Pattern.LAUNCH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 150)
                    .compose()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    @Suppress("DEPRECATION")
                    VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
        }

        try {
            vibrator.vibrate(effect)
        } catch (_: Exception) {
        }
    }
}
