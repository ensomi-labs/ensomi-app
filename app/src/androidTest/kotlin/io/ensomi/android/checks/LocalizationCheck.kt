package io.ensomi.android.checks

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ensomi.android.R
import io.ensomi.android.model.InstallPhase
import io.ensomi.android.model.ModelFailure
import io.ensomi.android.model.description
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationCheck {
    @Test
    fun simplifiedChineseAndJapaneseResolveAcrossTheApp() {
        listOf("zh-CN", "zh-SG", "zh-Hans").forEach { tag ->
            val res = resources(tag)
            assertEquals("设置", res.getString(R.string.settings_title))
            assertEquals("完美", res.getString(R.string.judgement_perfect))
            assertEquals("返回", res.getString(R.string.recognition_back))
            assertEquals("最近 1 次触摸命中", res.getQuantityString(R.plurals.calibration_last_touch, 1, 1))
            assertEquals("最近 2 次触摸命中", res.getQuantityString(R.plurals.calibration_last_touch, 2, 2))
        }
        val ja = resources("ja-JP")
        assertEquals("設定", ja.getString(R.string.settings_title))
        assertEquals("パーフェクト", ja.getString(R.string.judgement_perfect))
        assertEquals("戻る", ja.getString(R.string.recognition_back))
        assertEquals("直近 1 回のタッチヒット", ja.getQuantityString(R.plurals.calibration_last_touch, 1, 1))
        assertTrue(ja.getString(R.string.recognition_failure_no_file).contains("ダウンロード"))
    }

    @Test
    fun retainedFailuresResolveAgainAfterLanguageChanges() {
        val failure = ModelFailure.AtStage(InstallPhase.VERIFYING, ModelFailure.Http(503))
        val en = failure.description(resources("en"))
        val zh = failure.description(resources("zh-Hans"))
        val ja = failure.description(resources("ja"))
        assertTrue(en.contains("Checking the model failed"))
        assertTrue(zh.contains("校验模型失败"))
        assertTrue(ja.contains("モデルの検証に失敗"))
        listOf(en, zh, ja).forEach { assertTrue(it.contains("503")) }
        assertFalse(zh.contains("Checking"))
        assertFalse(ja.contains("Checking"))
    }

    @Test
    fun unsupportedLanguagesFallBackToEnglishAndTheDedicationStaysExact() {
        assertEquals("Settings", resources("de").getString(R.string.settings_title))
        val en = resources("en")
        listOf("zh-Hans", "ja").forEach { tag ->
            val res = resources(tag)
            listOf(R.string.app_name, R.string.settings_for_lucky, R.string.lucky_title,
                R.string.lucky_in_memory, R.string.lucky_dedication).forEach { id ->
                assertEquals(en.getString(id), res.getString(id))
            }
        }
    }

    private fun resources(tag: String): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        return context.createConfigurationContext(config).resources
    }
}
