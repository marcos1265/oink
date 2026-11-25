package com.hachiwii.oink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hachiwii.oink.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class L10nTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun systemResolvesAllConfiguredLanguagesAndDefaultsOtherLocalesToEnglish() {
        assertEquals(AppLang.ZH, AppLang.resolve("system", Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLang.EN, AppLang.resolve("system", Locale.US))
        assertEquals(AppLang.JA, AppLang.resolve("system", Locale.JAPAN))
        assertEquals(AppLang.KO, AppLang.resolve("system", Locale.KOREA))
        assertEquals(AppLang.EN, AppLang.resolve("system", Locale.forLanguageTag("es-ES")))

        // Explicit settings must override any host system locale.
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.US, Locale.JAPAN, Locale.KOREA).forEach { loc ->
            assertEquals(AppLang.ZH, AppLang.resolve("zh-Hans", loc))
            assertEquals(AppLang.EN, AppLang.resolve("en", loc))
            assertEquals(AppLang.JA, AppLang.resolve("ja", loc))
            assertEquals(AppLang.KO, AppLang.resolve("ko", loc))
        }
    }

    @Test
    fun everyMapKeyHasEnJaKoTranslationsAndValidPatterns() {
        assertTrue(L10n.EN_MAP.isNotEmpty())
        val missingJa = L10n.EN_MAP.keys - L10n.JA_MAP.keys
        val missingKo = L10n.EN_MAP.keys - L10n.KO_MAP.keys
        assertTrue("Missing JA keys: $missingJa", missingJa.isEmpty())
        assertTrue("Missing KO keys: $missingKo", missingKo.isEmpty())

        L10n.EN_MAP.forEach { (k, v) ->
            assertFalse("EN value still has Han ideograph for '$k': '$v'", L10n.containsCjk(v))
        }
        L10n.KO_MAP.forEach { (k, v) ->
            assertFalse("KO value still has Han ideograph for '$k': '$v'", L10n.containsCjk(v))
        }

        // Key patterns across EN / JA / KO
        assertEquals("2 devices online · Audio exit · Microphone shared", L10n.translate("2 在线设备 · 音频出口 · 麦克风共享", AppLang.EN))
        assertEquals("オンライン 2 台 · 音声出力 · マイク共有中", L10n.translate("2 在线设备 · 音频出口 · 麦克风共享", AppLang.JA))
        assertEquals("온라인 2대 · 오디오 출력 · 마이크 공유", L10n.translate("2 在线设备 · 音频出口 · 麦克风共享", AppLang.KO))

        assertEquals("Sent to Phone", L10n.translate("已发送给 Phone", AppLang.EN))
        assertEquals("Phone に送信済み", L10n.translate("已发送给 Phone", AppLang.JA))
        assertEquals("Phone에게 전송됨", L10n.translate("已发送给 Phone", AppLang.KO))

        assertEquals("Default: Pixel", L10n.translate("默认：Pixel", AppLang.EN))
        assertEquals("No app found to open test.apk", L10n.translate("没有可以打开 test.apk 的应用", AppLang.EN))
        assertEquals("Quit", L10n.translate("退出", AppLang.EN))
        assertEquals("Button mapping", L10n.translate("按键映射", AppLang.EN))
        assertEquals("Edit buttons", L10n.translate("编辑按键", AppLang.EN))
        assertEquals("Latency 12 ms", L10n.translate("延迟 12 毫秒", AppLang.EN))
        assertEquals("Open My PC controls", L10n.translate("打开 My PC 设备控制", AppLang.EN))

        assertEquals("Setting declined: Peer state changed, please retry", L10n.translate("设置被拒绝：对方的状态已变化，请重试", AppLang.EN))
        assertEquals("設定が拒否されました：状態が変化しました。再試行してください", L10n.translate("设置被拒绝：对方的状态已变化，请重试", AppLang.JA))
        assertEquals("설정이 거부됨: 상대방 상태가 변경됨, 다시 시도하세요", L10n.translate("设置被拒绝：对方的状态已变化，请重试", AppLang.KO))

        assertEquals("Setting failed: Device is offline", L10n.translate("设置失败：设备不在线", AppLang.EN))
        assertEquals("設定に失敗しました：デバイスはオフラインです", L10n.translate("设置失败：设备不在线", AppLang.JA))
        assertEquals("설정 실패: 기기가 오프라인입니다", L10n.translate("设置失败：设备不在线", AppLang.KO))

        assertEquals("MyPC output volume", L10n.translate("MyPC 输出音量", AppLang.EN))
        assertEquals("MyPC 出力音量", L10n.translate("MyPC 输出音量", AppLang.JA))
        assertEquals("MyPC 출력 음량", L10n.translate("MyPC 输出音量", AppLang.KO))

        assertEquals("Peer lacks capability: audio", L10n.translate("对端缺少能力：audio", AppLang.EN))
        assertEquals("相手側に必要な機能がありません：audio", L10n.translate("对端缺少能力：audio", AppLang.JA))
        assertEquals("상대방에게 필요한 기능이 없음: audio", L10n.translate("对端缺少能力：audio", AppLang.KO))

        assertEquals("Could not save to Photos: Unknown error", L10n.translate("无法保存到相册：未知错误", AppLang.EN))
        assertEquals("写真に保存できませんでした：不明なエラー", L10n.translate("无法保存到相册：未知错误", AppLang.JA))
        assertEquals("사진에 저장할 수 없음: 알 수 없는 오류", L10n.translate("无法保存到相册：未知错误", AppLang.KO))
    }

    @Test
    fun composeLiveLanguageSwitchUpdatesUiAndPreservesRawUserContent() {
        val orig = Locale.getDefault()
        try {
            var prefs by mutableStateOf(Prefs(scheme = "light", language = "zh-Hans"))
            var quitCount = 0
            val chineseDevice = PeerUi("pc1", "我的工作电脑", "windows", latency = 5.0)
            val chineseMsg = MessageUi(1L, false, "pc1", "我的工作电脑", "windows", true, "text",
                text = "这是一条用户发送的中文消息", sent = true)
            val model = AppModel(peers = listOf(chineseDevice), messages = listOf(chineseMsg))

            compose.setContent {
                OinkApp(model, UiInit(tab = Tab.Devices), prefs, { prefs = it }, AppActions(quitApp = { quitCount++ }), false)
            }
            compose.waitForIdle()

            // Simplified Chinese
            compose.onNodeWithText("设备").assertExists()
            compose.onNodeWithText("我的工作电脑").assertExists()

            // Switch live -> English (raw device name preserved)
            prefs = prefs.copy(language = "en")
            compose.waitForIdle()
            compose.onNodeWithText("Devices").assertExists()
            compose.onNodeWithText("Transfer").assertExists()
            compose.onNodeWithText("Control").assertExists()
            compose.onAllNodesWithText("设备").assertCountEquals(0)
            compose.onNodeWithText("我的工作电脑").assertExists()

            // Verify raw user message preserved in Transfer too
            compose.onNodeWithContentDescription("Transfer").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("这是一条用户发送的中文消息").assertExists()

            // Switch live -> Japanese
            prefs = prefs.copy(language = "ja")
            compose.waitForIdle()
            compose.onNodeWithText("デバイス").assertExists()
            compose.onNodeWithText("転送").assertExists()
            compose.onNodeWithText("操作").assertExists()
            compose.onNodeWithText("这是一条用户发送的中文消息").assertExists()

            // Switch live -> Korean
            prefs = prefs.copy(language = "ko")
            compose.waitForIdle()
            compose.onNodeWithText("기기").assertExists()
            compose.onNodeWithText("전송").assertExists()
            compose.onNodeWithText("제어").assertExists()

            // Return to Devices tab (which hosts TopBar settings gear), open Settings -> Quit Oink
            compose.onNodeWithContentDescription("기기").performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("설정 열기").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Oink 종료").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, quitCount)
        } finally {
            Locale.setDefault(orig)
            L10n.setPreference("system", orig)
        }
    }
}
