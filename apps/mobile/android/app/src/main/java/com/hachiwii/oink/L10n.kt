package com.hachiwii.oink

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLang(val code: String) {
    ZH("zh-Hans"),
    EN("en"),
    JA("ja"),
    KO("ko");

    companion object {
        fun resolve(pref: String?, locale: Locale = Locale.getDefault()): AppLang = when (pref) {
            "zh-Hans", "zh" -> ZH
            "en" -> EN
            "ja" -> JA
            "ko" -> KO
            else -> when (locale.language.lowercase(Locale.US)) {
                "zh" -> ZH
                "ja" -> JA
                "ko" -> KO
                else -> EN
            }
        }
    }
}

val LocalAppLang = compositionLocalOf { L10n.current }

/** Central localization hub for all Android UI, notifications, Quick Settings tiles, and error strings. */
object L10n {
    private val _langFlow = MutableStateFlow(AppLang.resolve("system"))
    val langFlow: StateFlow<AppLang> = _langFlow.asStateFlow()

    @Volatile
    var current: AppLang = _langFlow.value
        private set

    fun setPreference(code: String?, locale: Locale = Locale.getDefault()) {
        val next = AppLang.resolve(code, locale)
        current = next
        _langFlow.value = next
    }

    fun sync(context: Context) {
        val pref = runCatching { LocalSettings(context).language }.getOrDefault("system")
        setPreference(pref)
    }

    fun tr(zh: String, en: String, ja: String, ko: String, lang: AppLang = current): String = when (lang) {
        AppLang.ZH -> zh
        AppLang.EN -> en
        AppLang.JA -> ja
        AppLang.KO -> ko
    }

    /** Translates any Oink UI string from its Simplified Chinese source string into [lang]. */
    fun translate(text: String, lang: AppLang = current): String {
        if (lang == AppLang.ZH || text.isEmpty() || !containsCjk(text)) return text
        val map = when (lang) {
            AppLang.ZH -> return text
            AppLang.EN -> EN_MAP
            AppLang.JA -> JA_MAP
            AppLang.KO -> KO_MAP
        }
        map[text]?.let { return it }
        translatePattern(text, lang, map)?.let { return it }
        return text
    }

    internal fun containsCjk(s: String): Boolean {
        for (i in 0 until s.length) {
            if (s[i] in '\u4E00'..'\u9FFF') return true
        }
        return false
    }

    private fun word(key: String, lang: AppLang): String = when (lang) {
        AppLang.ZH -> key
        AppLang.EN -> EN_MAP[key] ?: key
        AppLang.JA -> JA_MAP[key] ?: key
        AppLang.KO -> KO_MAP[key] ?: key
    }

    private fun colon(lang: AppLang): String = when (lang) {
        AppLang.EN -> ": "
        AppLang.JA, AppLang.ZH -> "："
        AppLang.KO -> ": "
    }

    private fun countPlural(n: Int, singular: String, plural: String) = if (n == 1) "$n $singular" else "$n $plural"

    private fun translatePattern(text: String, lang: AppLang, map: Map<String, String>): String? {
        // Multiline / special license footer
        if (text.startsWith("Oink 使用了以上开源项目，感谢它们的作者。\n")) {
            val suffix = text.substringAfter('\n')
            val head = word("Oink 使用了以上开源项目，感谢它们的作者。", lang)
            return "$head\n$suffix"
        }

        // Notification compound status line: "X 在线设备 · ..."
        R_ONLINE_COMPOUND.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val head = when (lang) {
                AppLang.ZH -> "$n 在线设备"
                AppLang.EN -> if (n == 1) "1 device online" else "$n devices online"
                AppLang.JA -> "オンライン $n 台"
                AppLang.KO -> "온라인 ${n}대"
            }
            val rest = m.groupValues.getOrNull(2).orEmpty()
            if (rest.isEmpty()) return head
            val parts = rest.split(" · ").map { translate(it, lang) }
            return (listOf(head) + parts).joinToString(" · ")
        }

        // Dates in TransferPage
        R_TODAY.matchEntire(text)?.let { m ->
            val t = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "Today · $t"
                AppLang.JA -> "今日 · $t"
                AppLang.KO -> "오늘 · $t"
                AppLang.ZH -> text
            }
        }
        R_YESTERDAY.matchEntire(text)?.let { m ->
            val t = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "Yesterday · $t"
                AppLang.JA -> "昨日 · $t"
                AppLang.KO -> "어제 · $t"
                AppLang.ZH -> text
            }
        }
        R_MONTH_DAY.matchEntire(text)?.let { m ->
            val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt(); val t = m.groupValues[3]
            return when (lang) {
                AppLang.EN -> "${EN_MONTHS.getOrElse(mo - 1) { "$mo" }} $d · $t"
                AppLang.JA -> "${mo}月${d}日 · $t"
                AppLang.KO -> "${mo}월 ${d}일 · $t"
                AppLang.ZH -> text
            }
        }
        R_YEAR_MONTH_DAY.matchEntire(text)?.let { m ->
            val y = m.groupValues[1]; val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt(); val t = m.groupValues[4]
            return when (lang) {
                AppLang.EN -> "${EN_MONTHS.getOrElse(mo - 1) { "$mo" }} $d, $y · $t"
                AppLang.JA -> "${y}年${mo}月${d}日 · $t"
                AppLang.KO -> "${y}년 ${mo}월 ${d}일 · $t"
                AppLang.ZH -> text
            }
        }

        // Last seen / time durations
        R_MINUTES.matchEntire(text)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { AppLang.EN -> "$n min"; AppLang.JA -> "${n}分"; AppLang.KO -> "${n}분"; AppLang.ZH -> text }
        }
        R_HOURS.matchEntire(text)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { AppLang.EN -> "$n hr"; AppLang.JA -> "${n}時間"; AppLang.KO -> "${n}시간"; AppLang.ZH -> text }
        }
        R_DAYS.matchEntire(text)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { AppLang.EN -> "${n}d"; AppLang.JA -> "${n}日"; AppLang.KO -> "${n}일"; AppLang.ZH -> text }
        }
        R_LAST_SEEN_ROW.matchEntire(text)?.let { m ->
            val d = translate(m.groupValues[1], lang)
            return when (lang) { AppLang.EN -> "Last seen · $d ago"; AppLang.JA -> "最終オンライン · ${d}前"; AppLang.KO -> "마지막 온라인 · $d 전"; AppLang.ZH -> text }
        }
        R_LAST_SEEN_SHEET.matchEntire(text)?.let { m ->
            val d = translate(m.groupValues[1], lang)
            return when (lang) { AppLang.EN -> "Last seen: $d ago"; AppLang.JA -> "最終オンライン：${d}前"; AppLang.KO -> "마지막 온라인: $d 전"; AppLang.ZH -> text }
        }

        // Transfer batch, item counts, progress, direct notices
        R_FILE_COUNT.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (lang) { AppLang.EN -> countPlural(n, "file", "files"); AppLang.JA -> "${n} 個のファイル"; AppLang.KO -> "파일 ${n}개"; AppLang.ZH -> text }
        }
        R_PHOTO_COUNT.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (lang) { AppLang.EN -> countPlural(n, "photo", "photos"); AppLang.JA -> "${n} 枚の写真"; AppLang.KO -> "사진 ${n}장"; AppLang.ZH -> text }
        }
        R_ITEMS_SHORT.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (lang) { AppLang.EN -> countPlural(n, "item", "items"); AppLang.JA -> "${n} 件"; AppLang.KO -> "${n}개"; AppLang.ZH -> text }
        }
        R_EXPAND_ALL.matchEntire(text)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Expand all $n items"; AppLang.JA -> "すべて展開（$n 件）"; AppLang.KO -> "전체 ${n}개 펼치기"; AppLang.ZH -> text }
        }
        R_STACK_DESC.matchEntire(text)?.let { m ->
            val total = m.groupValues[1]; val idx = m.groupValues[2]; val name = m.groupValues[3]
            return when (lang) {
                AppLang.EN -> "$total items, item $idx: $name. Swipe left or right to switch, tap to open"
                AppLang.JA -> "$total 件中 $idx 件目：$name。左右にスワイプして切り替え、タップで開く"
                AppLang.KO -> "${total}개 중 ${idx}번째: $name. 좌우로 스와이프하여 전환하고 탭하여 열기"
                AppLang.ZH -> text
            }
        }
        R_PDF_PAGE.matchEntire(text)?.let { m ->
            val p = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Page $p"; AppLang.JA -> "${p} ページ"; AppLang.KO -> "${p}페이지"; AppLang.ZH -> text }
        }
        R_COMPOSER_ALL_PASTE.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "Send to $name (paste on all devices)"
                AppLang.JA -> "$name に送信（すべてのデバイスにペースト可能）"
                AppLang.KO -> "$name(으)로 보내기 (모든 기기에서 붙여넣기 가능)"
                AppLang.ZH -> text
            }
        }
        R_COMPOSER_TO.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Send to $name"; AppLang.JA -> "$name に送信"; AppLang.KO -> "$name(으)로 보내기"; AppLang.ZH -> text }
        }
        R_SWITCHED_ACTIVE.matchEntire(text)?.let { m ->
            val from = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "$from is offline; switched to active"
                AppLang.JA -> "$from はオフラインのためアクティブに切り替えました"
                AppLang.KO -> "${from}이(가) 오프라인이라 활성 기기로 전환됨"
                AppLang.ZH -> text
            }
        }
        R_OFFLINE_CANT_SEND.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "$name is offline, unable to send right now"
                AppLang.JA -> "$name はオフラインのため今は送信できません"
                AppLang.KO -> "${name}이(가) 오프라인이라 지금은 보낼 수 없습니다"
                AppLang.ZH -> text
            }
        }
        R_IS_OFFLINE.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "$name is offline"; AppLang.JA -> "$name はオフライン"; AppLang.KO -> "$name 오프라인"; AppLang.ZH -> text }
        }
        R_SENDING_TO_PCT.matchEntire(text)?.let { m ->
            val to = m.groupValues[1]; val pct = m.groupValues[2]
            return when (lang) { AppLang.EN -> "Sending to $to · $pct%"; AppLang.JA -> "$to に送信中 · $pct%"; AppLang.KO -> "${to}에게 보내는 중 · $pct%"; AppLang.ZH -> text }
        }
        R_SENDING_TO_DOTS.matchEntire(text)?.let { m ->
            val to = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Sending to $to…"; AppLang.JA -> "$to に送信中…"; AppLang.KO -> "${to}에게 보내는 중…"; AppLang.ZH -> text }
        }
        R_SENDING_TO.matchEntire(text)?.let { m ->
            val to = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Sending to $to"; AppLang.JA -> "$to に送信中"; AppLang.KO -> "${to}에게 보내는 중"; AppLang.ZH -> text }
        }
        R_SENDING_PCT.matchEntire(text)?.let { m ->
            val pct = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Sending $pct%"; AppLang.JA -> "送信中 $pct%"; AppLang.KO -> "전송 중 $pct%"; AppLang.ZH -> text }
        }
        R_SENT_ELAPSED.matchEntire(text)?.let { m ->
            val base = m.groupValues[1]; val sec = m.groupValues[2]
            val head = if (base == "已完成") word("已完成", lang)
            else {
                val to = base.removePrefix("已发送给 ")
                when (lang) { AppLang.EN -> "Sent to $to"; AppLang.JA -> "$to に送信済み"; AppLang.KO -> "${to}에게 전송됨"; AppLang.ZH -> base }
            }
            return if (sec.isEmpty()) head else when (lang) {
                AppLang.EN -> "$head in $sec s"
                AppLang.JA -> "$head（$sec 秒）"
                AppLang.KO -> "$head (${sec}초)"
                AppLang.ZH -> text
            }
        }
        R_SENT_TO.matchEntire(text)?.let { m ->
            val to = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Sent to $to"; AppLang.JA -> "$to に送信済み"; AppLang.KO -> "${to}에게 전송됨"; AppLang.ZH -> text }
        }
        R_RECEIVED_COUNT.matchEntire(text)?.let { m ->
            val count = m.groupValues[1]; val first = m.groupValues[2]
            return when (lang) {
                AppLang.EN -> "Sent $count files: $first, etc."
                AppLang.JA -> "$count 個のファイルを受信：$first ほか"
                AppLang.KO -> "파일 ${count}개 보냄: $first 등"
                AppLang.ZH -> text
            }
        }
        R_RECEIVED_SINGLE.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Sent $name"; AppLang.JA -> "$name を受信"; AppLang.KO -> "$name 보냄"; AppLang.ZH -> text }
        }
        R_IMAGE_PLACEHOLDER.matchEntire(text)?.let { m ->
            val alt = translate(m.groupValues[1], lang)
            return when (lang) { AppLang.EN -> "[Image: $alt]"; AppLang.JA -> "[画像：$alt]"; AppLang.KO -> "[이미지: $alt]"; AppLang.ZH -> text }
        }
        R_PROGRESS_BYTES.matchEntire(text)?.let { m ->
            val bytes = m.groupValues[1]; val total = if (m.groupValues[2] == "未知") word("未知", lang) else m.groupValues[2]
            return when (lang) { AppLang.EN -> "$bytes / $total bytes"; AppLang.JA -> "$bytes / $total バイト"; AppLang.KO -> "$bytes / ${total}바이트"; AppLang.ZH -> text }
        }

        // Settings & dialogs
        R_CLEAR_CACHE_DONE.matchEntire(text)?.let { m ->
            val size = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Cleared $size"; AppLang.JA -> "$size を消去しました"; AppLang.KO -> "$size 삭제됨"; AppLang.ZH -> text }
        }
        R_CLEAR_CACHE_BODY.matchEntire(text)?.let { m ->
            val size = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "Deletes $size of downloaded images and files. Message history is kept."
                AppLang.JA -> "ダウンロード済みの画像とファイル（$size）を削除します。メッセージ履歴は残ります。"
                AppLang.KO -> "다운로드된 이미지와 파일($size)을 삭제합니다. 메시지 기록은 유지됩니다."
                AppLang.ZH -> text
            }
        }
        R_PAIR_PORT.matchEntire(text)?.let { m ->
            val port = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Pairing service found · port $port"; AppLang.JA -> "ペアリングサービスが見つかりました · ポート $port"; AppLang.KO -> "페어링 서비스 찾음 · 포트 $port"; AppLang.ZH -> text }
        }
        R_DEFAULT_NAME.matchEntire(text)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Default: $n"; AppLang.JA -> "デフォルト：$n"; AppLang.KO -> "기본값: $n"; AppLang.ZH -> text }
        }
        R_PRIV_NO_POINTER.matchEntire(text)?.let { m ->
            val reason = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "Clipboard auto-read & broadcast works, but PC mouse control is unavailable: $reason"
                AppLang.JA -> "クリップボードの自動読み取りと共有は動作しますが、PC からのマウス操作は一時的に利用できません：$reason"
                AppLang.KO -> "클립보드 자동 읽기 및 공유는 작동하지만 PC 마우스 제어는 일시적으로 사용할 수 없습니다: $reason"
                AppLang.ZH -> text
            }
        }
        R_ATTEMPTS_LEFT.matchEntire(text)?.let { m ->
            val left = m.groupValues[1].toInt()
            return when (lang) {
                AppLang.EN -> if (left == 1) "1 attempt left" else "$left attempts left"
                AppLang.JA -> "あと $left 回試せます"
                AppLang.KO -> "${left}회 더 시도할 수 있습니다"
                AppLang.ZH -> text
            }
        }
        R_WRONG_CODE_ATTEMPTS.matchEntire(text)?.let { m ->
            val left = m.groupValues[1].toInt()
            return when (lang) {
                AppLang.EN -> "Incorrect pairing code (${if (left == 1) "1 attempt left" else "$left attempts left"})"
                AppLang.JA -> "コードが正しくありません（あと $left 回）"
                AppLang.KO -> "페어링 코드가 올바르지 않습니다 (${left}회 남음)"
                AppLang.ZH -> text
            }
        }
        R_CHECK_CODE_ATTEMPTS.matchEntire(text)?.let { m ->
            val left = m.groupValues[1].toInt()
            return when (lang) {
                AppLang.EN -> "Incorrect code (${if (left == 1) "1 attempt left" else "$left attempts left"})"
                AppLang.JA -> "認証コードが正しくありません（あと $left 回）"
                AppLang.KO -> "인증 코드가 올바르지 않습니다 (${left}회 남음)"
                AppLang.ZH -> text
            }
        }

        // Pairing, Add device, Device sheet, Control
        R_GROUP_SIZE.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (lang) { AppLang.EN -> countPlural(n, "device in group", "devices in group"); AppLang.JA -> "グループ内 $n 台"; AppLang.KO -> "그룹 내 기기 ${n}대"; AppLang.ZH -> text }
        }
        R_REJECT_PAIR.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Decline pairing request from $name"; AppLang.JA -> "$name のペアリング要求を拒否"; AppLang.KO -> "${name}의 페어링 요청 거절"; AppLang.ZH -> text }
        }
        R_ACCEPT_PAIR.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Accept pairing request from $name"; AppLang.JA -> "$name のペアリング要求を承認"; AppLang.KO -> "${name}의 페어링 요청 수락"; AppLang.ZH -> text }
        }
        R_PAIR_WITH.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Pair with $name"; AppLang.JA -> "$name とペアリング"; AppLang.KO -> "${name}와(과) 페어링"; AppLang.ZH -> text }
        }
        R_ENTER_ON_PEER.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Enter this pairing code on $name"; AppLang.JA -> "$name でこのペアリングコードを入力"; AppLang.KO -> "${name}에 이 페어링 코드 입력"; AppLang.ZH -> text }
        }
        R_ENTER_FROM_PEER.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Enter the pairing code shown on $name"; AppLang.JA -> "$name の画面に表示されたコードを入力"; AppLang.KO -> "$name 화면에 표시된 페어링 코드 입력"; AppLang.ZH -> text }
        }
        R_PAIRED_WITH.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Paired with $name"; AppLang.JA -> "$name とペアリングしました"; AppLang.KO -> "${name}와(과) 페어링됨"; AppLang.ZH -> text }
        }
        R_CANT_PAIR_WITH.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Could not pair with $name"; AppLang.JA -> "$name とペアリングできません"; AppLang.KO -> "${name}와(과) 페어링할 수 없습니다"; AppLang.ZH -> text }
        }
        R_WAIT_PEER_STATE.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Waiting for $name’s state, please try again shortly"; AppLang.JA -> "$name の状態をまだ受信していません。しばらくお待ちください"; AppLang.KO -> "${name}의 상태를 아직 받지 못했습니다. 잠시 후 다시 시도하세요"; AppLang.ZH -> text }
        }
        R_SHOW_ALL_IPS.matchEntire(text)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Show all $n"; AppLang.JA -> "すべて表示（$n）"; AppLang.KO -> "전체 ${n}개 보기"; AppLang.ZH -> text }
        }
        R_TAP_TO_COPY.matchEntire(text)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) { AppLang.EN -> "$v, tap to copy"; AppLang.JA -> "$v、タップでコピー"; AppLang.KO -> "$v, 탭하여 복사"; AppLang.ZH -> text }
        }
        R_COPIED_VAL.matchEntire(text)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Copied $v"; AppLang.JA -> "$v をコピーしました"; AppLang.KO -> "$v 복사됨"; AppLang.ZH -> text }
        }
        R_KICK_PEER.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Remove $name from group"; AppLang.JA -> "$name をグループから削除"; AppLang.KO -> "그룹에서 $name 제거"; AppLang.ZH -> text }
        }
        R_OPEN_DEVICE_CTRL.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Open $name controls"; AppLang.JA -> "$name の操作を開く"; AppLang.KO -> "$name 기기 제어 열기"; AppLang.ZH -> text }
        }
        R_ADD_REQ_COUNT.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            return when (lang) {
                AppLang.EN -> "Add device, " + countPlural(n, "pairing request", "pairing requests")
                AppLang.JA -> "デバイスを追加、ペアリング要求 $n 件"
                AppLang.KO -> "기기 추가, 페어링 요청 ${n}건"
                AppLang.ZH -> text
            }
        }
        R_LATENCY_MS.matchEntire(text)?.let { m ->
            val ms = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Latency $ms ms"; AppLang.JA -> "遅延 $ms ミリ秒"; AppLang.KO -> "지연 시간 ${ms}밀리초"; AppLang.ZH -> text }
        }
        R_RETRY_DOWNLOAD.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Retry downloading $name"; AppLang.JA -> "$name のダウンロードを再試行"; AppLang.KO -> "$name 다운로드 다시 시도"; AppLang.ZH -> text }
        }
        R_DOWNLOAD_NAME.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Download $name"; AppLang.JA -> "$name をダウンロード"; AppLang.KO -> "$name 다운로드"; AppLang.ZH -> text }
        }
        R_NO_APP_FOR.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "No app found to open $name"; AppLang.JA -> "$name を開けるアプリがありません"; AppLang.KO -> "${name}을(를) 열 수 있는 앱이 없습니다"; AppLang.ZH -> text }
        }
        R_CONNECTING_TO.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Connecting to $name"; AppLang.JA -> "$name に接続中"; AppLang.KO -> "${name}에 연결 중"; AppLang.ZH -> text }
        }
        R_SEND_TARGET_DESC.matchEntire(text)?.let { m ->
            val rest = translate(m.groupValues[1], lang)
            return "${word("发送对象", lang)}${colon(lang)}$rest"
        }
        R_FOCUS_DEVICE.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Focused device: $name"; AppLang.JA -> "フォーカスデバイス：$name"; AppLang.KO -> "포커스 기기: $name"; AppLang.ZH -> text }
        }
        R_LOCK_HINT.matchEntire(text)?.let { m ->
            val label = translate(m.groupValues[1], lang)
            val action = translate(m.groupValues[2], lang)
            return when (lang) {
                AppLang.EN -> "$label, tap to ${action.lowercase(Locale.US)}, long press for more"
                AppLang.JA -> "$label、タップで$action、長押しで詳細"
                AppLang.KO -> "$label, 탭하여 $action, 길게 눌러 더 보기"
                AppLang.ZH -> text
            }
        }
        R_KEY_LATCH.matchEntire(text)?.let { m ->
            val keyName = translate(m.groupValues[1], lang)
            val state = if (m.groupValues[2] == "按住") word("按住", lang) else word("锁定", lang)
            return when (lang) {
                AppLang.EN -> "$keyName ($state)"
                AppLang.JA -> "$keyName（$state）"
                AppLang.KO -> "$keyName ($state)"
                AppLang.ZH -> text
            }
        }
        R_SHORTCUT_COPY.matchEntire(text)?.let { m ->
            val sc = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Copy ($sc)"; AppLang.JA -> "コピー（$sc）"; AppLang.KO -> "복사 ($sc)"; AppLang.ZH -> text }
        }
        R_SHORTCUT_PASTE.matchEntire(text)?.let { m ->
            val sc = m.groupValues[1]
            return when (lang) { AppLang.EN -> "Paste ($sc)"; AppLang.JA -> "ペースト（$sc）"; AppLang.KO -> "붙여넣기 ($sc)"; AppLang.ZH -> text }
        }
        R_AUDIO_VOL_DESC.matchEntire(text)?.let { m ->
            val kind = translate(m.groupValues[1], lang)
            val rest = translate(m.groupValues[2], lang)
            return when (lang) { AppLang.EN -> "$kind volume $rest"; AppLang.JA -> "$kind 音量 $rest"; AppLang.KO -> "$kind 음량 $rest"; AppLang.ZH -> text }
        }
        R_OUTPUT_VOLUME.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) { AppLang.EN -> "$name output volume"; AppLang.JA -> "$name 出力音量"; AppLang.KO -> "$name 출력 음량"; AppLang.ZH -> text }
        }
        R_BLOCK_FULL.matchEntire(text)?.let { m ->
            val limit = m.groupValues[1]
            return when (lang) {
                AppLang.EN -> "Block list is full (max $limit)"
                AppLang.JA -> "ブロックリストが上限に達しました（最大 $limit 台）"
                AppLang.KO -> "차단 목록이 가득 찼습니다(최대 ${limit}대)"
                AppLang.ZH -> text
            }
        }

        // Prefix + colon patterns
        for ((prefix, handler) in PREFIX_RULES) {
            if (text.startsWith(prefix)) {
                val tail = text.removePrefix(prefix)
                return handler(prefix, translate(tail, lang), lang)
            }
        }

        // Device resource summary in RustDeviceCards
        if (text.startsWith("屏幕：")) {
            return text.split("\n").joinToString("\n") { line ->
                line.split(" · ").joinToString(" · ") { part -> translate(part, lang) }
            }
        }

        // Generic "前缀：值" (handles 扬声器：开, 鼠标：shared, 配对失败：xxx, 对端缺少能力：xxx, 无法分享：xxx, etc.)
        val idx = text.indexOf('：')
        if (idx in 1 until text.length) {
            val head = text.substring(0, idx)
            val tail = text.substring(idx + 1)
            val mappedHead = map[head]
            if (mappedHead != null) {
                val mappedTail = if (containsCjk(tail)) translate(tail, lang) else (map[tail] ?: tail)
                return "$mappedHead${colon(lang)}$mappedTail"
            }
        }

        return null
    }

    private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val R_ONLINE_COMPOUND = Regex("""^(\d+) 在线设备(?: · (.+))?$""")
    private val R_TODAY = Regex("""^今天 · (.+)$""")
    private val R_YESTERDAY = Regex("""^昨天 · (.+)$""")
    private val R_MONTH_DAY = Regex("""^(\d+)月(\d+)日 · (.+)$""")
    private val R_YEAR_MONTH_DAY = Regex("""^(\d+)年(\d+)月(\d+)日 · (.+)$""")
    private val R_MINUTES = Regex("""^(\d+) 分钟$""")
    private val R_HOURS = Regex("""^(\d+) 小时$""")
    private val R_DAYS = Regex("""^(\d+) 天$""")
    private val R_LAST_SEEN_ROW = Regex("""^上次在线 · (.+)前$""")
    private val R_LAST_SEEN_SHEET = Regex("""^上次在线：(.+)前$""")
    private val R_FILE_COUNT = Regex("""^(\d+) 个文件$""")
    private val R_PHOTO_COUNT = Regex("""^(\d+) 张照片$""")
    private val R_ITEMS_SHORT = Regex("""^(\d+) 项$""")
    private val R_EXPAND_ALL = Regex("""^展开全部 (\d+) 项$""")
    private val R_STACK_DESC = Regex("""^(\d+) 项，当前第 (\d+) 项：(.+)。左右划动切换，轻点打开$""")
    private val R_PDF_PAGE = Regex("""^第 (\d+) 页$""")
    private val R_COMPOSER_ALL_PASTE = Regex("""^发送给 (.+)，可在所有设备粘贴$""")
    private val R_COMPOSER_TO = Regex("""^发送给 (.+)$""")
    private val R_SWITCHED_ACTIVE = Regex("""^(.+) 已离线，已切回当前活跃$""")
    private val R_OFFLINE_CANT_SEND = Regex("""^(.+) 已离线，暂时无法发送$""")
    private val R_IS_OFFLINE = Regex("""^(.+) 不在线$""")
    private val R_SENDING_TO_PCT = Regex("""^正在发送给 (.+) · (\d+)%$""")
    private val R_SENDING_TO_DOTS = Regex("""^正在发送给 (.+)…$""")
    private val R_SENDING_TO = Regex("""^正在发送给 (.+)$""")
    private val R_SENDING_PCT = Regex("""^发送进度 (\d+)%$""")
    private val R_SENT_ELAPSED = Regex("""^(已完成|已发送给 .+)，耗时 (.+) 秒$""")
    private val R_SENT_TO = Regex("""^已发送给 (.+)$""")
    private val R_RECEIVED_COUNT = Regex("""^发来 (\d+) 个文件：(.+) 等$""")
    private val R_RECEIVED_SINGLE = Regex("""^发来 (.+)$""")
    private val R_IMAGE_PLACEHOLDER = Regex("""^\[图片：(.+)]$""")
    private val R_PROGRESS_BYTES = Regex("""^(\d+) / (\d+|未知) 字节$""")
    private val R_CLEAR_CACHE_DONE = Regex("""^已清理 (.+)$""")
    private val R_CLEAR_CACHE_BODY = Regex("""^将删除已下载的图片和文件，共 (.+)。消息记录会保留。$""")
    private val R_PAIR_PORT = Regex("""^已找到配对服务 · 端口 (\d+)$""")
    private val R_DEFAULT_NAME = Regex("""^默认：(.+)$""")
    private val R_PRIV_NO_POINTER = Regex("""^剪贴板可以自动读取并广播，但电脑暂时不能用鼠标操控本机：(.+)$""")
    private val R_ATTEMPTS_LEFT = Regex("""^还可以再试 (\d+) 次$""")
    private val R_WRONG_CODE_ATTEMPTS = Regex("""^配对码不正确，还可以再试 (\d+) 次$""")
    private val R_CHECK_CODE_ATTEMPTS = Regex("""^验证码错误，还可尝试 (\d+) 次$""")
    private val R_GROUP_SIZE = Regex("""^设备组共 (\d+) 台$""")
    private val R_REJECT_PAIR = Regex("""^拒绝 (.+) 的配对请求$""")
    private val R_ACCEPT_PAIR = Regex("""^接受 (.+) 的配对请求$""")
    private val R_PAIR_WITH = Regex("""^与 (.+) 配对$""")
    private val R_ENTER_ON_PEER = Regex("""^在 (.+) 上输入这个配对码$""")
    private val R_ENTER_FROM_PEER = Regex("""^输入 (.+) 屏幕上显示的配对码$""")
    private val R_PAIRED_WITH = Regex("""^已与 (.+) 配对$""")
    private val R_CANT_PAIR_WITH = Regex("""^无法与 (.+) 配对$""")
    private val R_WAIT_PEER_STATE = Regex("""^还没有收到 (.+) 的状态，请稍候$""")
    private val R_SHOW_ALL_IPS = Regex("""^查看全部 (\d+) 个$""")
    private val R_TAP_TO_COPY = Regex("""^(.+)，点按复制$""")
    private val R_COPIED_VAL = Regex("""^已复制 (.+)$""")
    private val R_KICK_PEER = Regex("""^将 (.+) 踢出设备组$""")
    private val R_OPEN_DEVICE_CTRL = Regex("""^打开 (.+) 设备控制$""")
    private val R_ADD_REQ_COUNT = Regex("""^添加设备，(\d+) 个配对请求$""")
    private val R_LATENCY_MS = Regex("""^延迟 (\d+) 毫秒$""")
    private val R_RETRY_DOWNLOAD = Regex("""^重试下载 (.+)$""")
    private val R_DOWNLOAD_NAME = Regex("""^下载 (.+)$""")
    private val R_NO_APP_FOR = Regex("""^没有可以打开 (.+) 的应用$""")
    private val R_CONNECTING_TO = Regex("""^正在连接 (.+)$""")
    private val R_SEND_TARGET_DESC = Regex("""^发送对象：(.+)$""")
    private val R_FOCUS_DEVICE = Regex("""^焦点设备：(.+)$""")
    private val R_LOCK_HINT = Regex("""^(.+)，点按([^，]+)，长按更多$""")
    private val R_KEY_LATCH = Regex("""^(.+)（(按住|锁定)）$""")
    private val R_SHORTCUT_COPY = Regex("""^复制（(⌘C|Ctrl\+C)）$""")
    private val R_SHORTCUT_PASTE = Regex("""^粘贴（(⌘V|Ctrl\+V)）$""")
    private val R_AUDIO_VOL_DESC = Regex("""^(.+)音量 (.+)$""")
    private val R_OUTPUT_VOLUME = Regex("""^(.+) 输出音量$""")
    private val R_BLOCK_FULL = Regex("""^屏蔽列表已满（最多 (\d+) 台）$""")

    private val PREFIX_RULES: List<Pair<String, (String, String, AppLang) -> String>> = listOf(
        "无法读取 " to { _, tail, l -> when (l) { AppLang.EN -> "Could not read $tail"; AppLang.JA -> "$tail を読み取れません"; AppLang.KO -> "${tail}을(를) 읽을 수 없습니다"; AppLang.ZH -> "无法读取 $tail" } },
    )

    internal val EN_MAP: Map<String, String> = mapOf(
        // App & Tabs & Navigation
        "设备" to "Devices",
        "传输" to "Transfer",
        "控制" to "Control",
        "设置" to "Settings",
        "添加设备" to "Add device",
        "本机信息" to "Device info",
        "打开设置" to "Open Settings",
        "关闭设置" to "Close Settings",
        "返回" to "Back",
        "关闭" to "Close",
        "取消" to "Cancel",
        "确认" to "Confirm",
        "保存" to "Save",
        "知道了" to "Got it",
        "重试" to "Retry",
        "查找" to "Search",
        "配对" to "Pair",
        "取消配对" to "Cancel pairing",
        "退出" to "Quit",
        "退出 Oink" to "Quit Oink",
        "退出设备组" to "Leave group",

        // Persistent notification & Tiles & Foreground service
        "电脑连接" to "Computer connection",
        "打开 Oink" to "Open Oink",
        "复制内容" to "Copy clipboard",
        "停止接收音频" to "Stop audio output",
        "停止转发音频" to "Stop forwarding audio",
        "停止共享麦克风" to "Stop sharing mic",
        "Oink · 点按从共享剪贴板复制" to "Oink · Tap to copy shared clipboard",
        "Oink · 运行中" to "Oink · Running",
        "已复制" to "Copied",
        "转发音频" to "Forward audio",
        "音频出口" to "Audio exit",
        "麦克风共享" to "Microphone shared",
        "复制" to "Copy",
        "粘贴" to "Paste",
        "接收音频" to "Receive audio",
        "共享麦克风" to "Share mic",
        "Oink 未运行" to "Oink not running",
        "未配对" to "Not paired",
        "需特权模式" to "Needs privileged",
        "转发中" to "Forwarding",
        "获取中" to "Copying…",
        "没有内容" to "Empty",
        "复制失败" to "Copy failed",
        "发送中" to "Sending…",
        "已发送" to "Sent",
        "发送失败" to "Send failed",
        "收到的文件" to "Received files",
        "发送进度" to "Sending progress",

        // Connection status & Errors
        "正在连接…" to "Connecting…",
        "已连接局域网" to "Connected on LAN",
        "网络出错，正在重新连接…" to "Network error, reconnecting…",
        "连接离线" to "Disconnected",
        "网络绑定失败，使用系统默认网络" to "Failed to bind network, using default",
        "局域网发现启动失败" to "Failed to start LAN discovery",
        "停止广播失败" to "Failed to stop advertising",
        "设备广播失败" to "Device advertising failed",
        "无法读取传输设置" to "Could not load transfer settings",
        "无法读取设备组" to "Could not load device group",
        "无法离开设备组" to "Could not leave device group",
        "本机 · 离线" to "This device · Offline",
        "本机" to "This device",
        "离线" to "Offline",
        "在线" to "Online",
        "正在连接" to "Connecting",
        "选择期间 Oink 被系统关闭，请重新选择" to "Oink was closed by Android during selection, please pick again",
        "Rust 核心尚未安装，请先生成移动绑定" to "Rust core is not installed",
        "无法读取音频设置" to "Could not load audio settings",
        "传输失败，请重试" to "Transfer failed, please retry",
        "下载失败，请重试" to "Download failed, please retry",
        "发现结果更新失败" to "Failed to update discovery result",
        "发现结果撤回失败" to "Failed to withdraw discovery result",
        "剪贴板历史同步失败" to "Failed to sync clipboard history",
        "连接尚未就绪，请稍候" to "Connection not ready yet, please wait",
        "操作失败" to "Operation failed",
        "音频出口音量暂不可调" to "Audio exit volume cannot be adjusted right now",
        "无法修改设备名" to "Could not rename device",
        "无法修改自动接收设置" to "Could not update auto-clipboard setting",
        "转发音频仅在特权模式可用" to "Audio forwarding requires Privileged Mode",
        "请在 Oink 中允许使用麦克风" to "Please allow microphone permission in Oink",
        "没有麦克风权限，无法开启麦克风" to "Microphone permission denied",
        "无法统计缓存" to "Could not calculate cache size",
        "无法读取传输记录" to "Could not read transfer history",
        "无法删除消息" to "Could not delete message",
        "无法清理传输缓存" to "Could not clear transfer cache",
        "无法清理传输记录" to "Could not clear transfer history",
        "下载任务暂不可用" to "Download is unavailable right now",
        "请稍后重试" to "Please try again later",
        "无法打开文件" to "Could not open file",
        "此设备的麦克风音量暂不可调" to "Microphone volume cannot be adjusted for this device",
        "此设备的扬声器音量暂不可调" to "Speaker volume cannot be adjusted for this device",
        "没有可用的应用" to "No app available",
        "此设备暂时无法暂停" to "This device cannot be paused right now",
        "无法修改 IP" to "Could not update IP",
        "未知错误" to "Unknown error",
        "未知" to "Unknown",
        "无法创建相册项目" to "Could not create media entry",
        "已保存到相册" to "Saved to Photos",
        "没有可复制的内容" to "Nothing to copy",
        "剪贴板是空的" to "Clipboard is empty",
        "剪贴板里的内容不能发送" to "Clipboard content cannot be sent",
        "分享收到的内容" to "Share received content",
        "Oink 没有在运行" to "Oink is not running",
        "与电脑的连接中断，请重试" to "Connection to PC lost, please retry",
        "键鼠共享已暂停，输入没有发出" to "Input sharing is paused",
        "这台设备不支持这项操作" to "Not supported on this device",
        "焦点电脑已暂停共享，文字没有输入" to "Focus PC paused sharing, text not entered",
        "焦点电脑没有加入键鼠共享，文字没有输入" to "Focus PC is not sharing input, text not entered",
        "这台设备不能输入文字" to "This device cannot receive text input",
        "转发音频失败：系统音频正被其它功能占用（如投屏）" to "Audio forwarding failed: system audio in use",
        "请解锁手机后再开始转发" to "Unlock your phone before forwarding audio",
        "转发音频已中断，请重试" to "Audio forwarding interrupted, please retry",
        "调节本机音量不影响转发" to "Phone volume does not affect forwarded audio",
        "转发中本机静音" to "Phone speaker muted while forwarding",
        "无法复制内容" to "Could not copy content",
        "无法写入手机剪贴板" to "Could not write to phone clipboard",
        "接收内容已过期，请重新获取" to "Content expired, please retry",
        "已接收并复制到手机剪贴板" to "Copied to clipboard",
        "正在准备分享…" to "Preparing share…",
        "正在读取内容，请完成后重新选择" to "Still reading previous content, please wait",
        "正在发送，请完成后重新分享" to "Another transfer is in progress, please wait",
        "已取消发送" to "Sending cancelled",
        "已取消" to "Cancelled",

        // Transfer / Clipboard / Errors
        "在线电脑没有可获取的剪贴板内容。请先在电脑上复制内容，再重试。" to "No clipboard content is available from an online computer. Copy something on the computer, then try again.",
        "连接中断，请重试" to "Connection lost, please retry",
        "没有在线的电脑" to "No computers online",
        "来源设备已离线，上线后再试" to "Source device is offline",
        "来源设备已不再保留此内容" to "Source device no longer keeps this item",
        "来源设备上已没有此内容" to "Item is no longer on source device",
        "此内容已被来源设备上的新内容替换" to "Replaced by newer content on source device",
        "来源设备无法提供此内容" to "Source device cannot provide this content",
        "共享内容已过期" to "Shared content expired",
        "这条消息已不存在" to "Message no longer exists",
        "本机发送的内容没有可下载的来源" to "No downloadable source for own item",
        "正在下载中" to "Downloading",
        "手机存储空间不足" to "Not enough storage on phone",
        "无法读取要发送的内容" to "Could not read content to send",
        "存储空间不足或无法写入" to "Storage full or write failed",
        "对方版本不兼容" to "Incompatible Oink version on peer",
        "对方已暂停共享" to "Peer paused sharing",
        "对方关了同步发送" to "Peer disabled sync send",
        "文件夹内容已失效" to "Folder is no longer available",
        "无法获取文件夹内容" to "Could not load folder",
        "没有在线的设备" to "No devices online",
        "对方没有响应" to "No response from peer",
        "对方拒绝接收" to "Peer declined",
        "对方无法接收" to "Peer failed to receive",
        "连接中断" to "Connection interrupted",
        "对方已断开" to "Peer disconnected",
        "对方已断开，发送失败" to "Disconnected, send failed",
        "对方已断开，接收失败" to "Disconnected, receive failed",
        "设备不在线" to "Device is offline",
        "对方的 Oink 版本不支持此操作" to "Peer’s Oink version does not support this",
        "对方的 Oink 版本不支持此设置" to "Peer’s Oink version does not support this setting",
        "还没有收到对方的状态，请稍候" to "Still waiting for device status",
        "对方的状态已过期，请稍候" to "Device status is stale, please wait",
        "对方设备出现故障" to "Peer device fault",
        "该设备不在设备组中" to "Device is not in the group",
        "对方正忙，请稍后再试" to "Peer is busy, try again later",
        "此操作暂不可用" to "Action unavailable right now",
        "请先在电脑上授予 Oink 所需权限" to "Grant required Oink permissions on the computer first",
        "IP 地址格式不正确" to "Invalid IP address format",
        "无法保存，请重试" to "Could not save, please retry",
        "不能屏蔽本机" to "Cannot block this device",
        "无法保存屏蔽列表，请重试" to "Could not save block list, please retry",
        "屏蔽列表操作失败" to "Block list operation failed",
        "正在获取…" to "Loading…",
        "获取失败" to "Failed to load",
        "版本过低，请升级" to "Version too old, please update",
        "上次在线" to "Recently online",
        "很久" to "a long time",

        // Devices page & Device sheet
        "还没有其它设备" to "No other devices yet",
        "添加一台电脑或手机，就能共享键鼠、剪贴板、文件和音频。" to "Add a computer or phone to share input, clipboard, files, and audio.",
        "Android，特权模式已开启" to "Android, privileged mode on",
        "鼠标" to "Mouse",
        "键盘" to "Keyboard",
        "音频" to "Audio",
        "麦克风" to "Mic",
        "扬声器" to "Speaker",
        "共享" to "Shared",
        "本地" to "Local",
        "禁用" to "Disabled",
        "已禁用" to "Disabled",
        "指针锁定" to "Pointer Lock",
        "开启" to "On",
        "关闭" to "Off",
        "游戏模式" to "Game Mode",
        "自动" to "Auto",
        "转发到出口" to "Forward to exit",
        "作为出口" to "Audio exit",
        "转发" to "Forward",
        "接收" to "Receive",
        "发送" to "Send",
        "开" to "On",
        "关" to "Off",
        "游戏模式：自动" to "Game Mode: Auto",
        "已暂停" to "Paused",
        "已继续" to "Resumed",
        "继续" to "Resume",
        "暂停" to "Pause",
        "踢出设备" to "Remove device",
        "无法远程控制此设备" to "This device cannot be remotely controlled",
        "编辑 IP" to "Edit IP",
        "踢出后，需要重新配对才能再次和它共享" to "You’ll need to pair again to share with this device",
        "踢出" to "Remove",
        "IP 已更新" to "IP updated",
        "IP 地址" to "IP address",
        "正在保存…" to "Saving…",
        "请输入 IP 地址" to "Enter an IP address",
        "请输入有效的 IP 地址" to "Enter a valid IP address",
        "端口需为 1–65535，或留空" to "Port must be 1–65535, or blank",
        "本地，音量固定为零" to "local, fixed at 0",

        // Add device & Pairing
        "配对请求" to "Pairing requests",
        "附近设备" to "Nearby devices",
        "刷新附近设备" to "Refresh nearby devices",
        "协议版本不兼容" to "Incompatible protocol version",
        "正在搜索附近的设备" to "Searching nearby devices",
        "手动添加" to "Add manually",
        "请确认对方已打开 Oink，并和本机连接到同一个网络。" to "Make sure Oink is open on the other device and on the same network.",
        "本机 IP" to "Local IP",
        "本机端口" to "Local port",
        "本机 ID" to "Device ID",
        "点按任一项即可复制" to "Tap any item to copy",
        "请输入有效的 IPv4 地址，例如 192.168.1.20" to "Enter a valid IPv4 address, e.g. 192.168.1.20",
        "端口应在 1–65535 之间" to "Port must be 1–65535",
        "输入对方的 IP 地址。对方的地址显示在它「添加设备」页的底部。" to "Enter the other device’s IP address shown on its Add Device screen.",
        "端口" to "Port",
        "正在查找…" to "Searching…",
        "配对失败" to "Pairing failed",
        "配对成功" to "Paired",
        "等待对方输入" to "Waiting for peer",
        "正在验证…" to "Verifying…",
        "配对后，对方可以和本机共享剪贴板、文件和音频；开启特权模式后还能操控本机。请确认你信任此设备。" to "After pairing, this device can share clipboard, files, audio, and control this phone in Privileged Mode. Make sure you trust it.",

        // Transfer page & Viewer
        "更早的记录仅保存在本机" to "Earlier records are stored only on this phone",
        "加载失败" to "Loading failed",
        "加载更早的记录" to "Load earlier records",
        "正在加载更早的记录…" to "Loading earlier records…",
        "已显示最近 100 条共享记录" to "Showing latest 100 shared records",
        "更早的本机记录" to "Earlier local records",
        "隐藏" to "Hide",
        "隐藏内容" to "Hide content",
        "收起" to "Collapse",
        "收起为一叠" to "Collapse into stack",
        "未选择" to "None",
        "添加附件" to "Add attachment",
        "当前活跃" to "Active device",
        "电脑" to "Computers",
        "移动设备" to "Mobile devices",
        "删除这条消息？" to "Delete this message?",
        "删除" to "Delete",
        "删除后无法恢复。" to "This cannot be undone.",
        "已下载的文件会一起删除，删除后无法恢复。" to "Downloaded files will be deleted too. This cannot be undone.",
        "拍摄" to "Camera",
        "图库" to "Gallery",
        "文件" to "Files",
        "文件夹" to "Folder",
        "取消发送" to "Cancel sending",
        "取消接收" to "Cancel receiving",
        "复制文本" to "Copy plain text",
        "选择" to "Select",
        "正在发送…" to "Sending…",
        "经" to "via",
        "已隐藏的内容" to "Hidden content",
        "查看更多" to "Show more",
        "显示" to "Show",
        "选择文字" to "Select text",
        "图片已失效" to "Image expired",
        "图片暂时无法加载" to "Image unavailable",
        "打开图片" to "Open image",
        "接收失败" to "Receive failed",
        "暂时无法加载" to "Unavailable for now",
        "加载失败，点按重试" to "Failed, tap to retry",
        "已失效" to "Expired",
        "下载失败" to "Download failed",
        "用其它应用打开" to "Open with another app",
        "分享" to "Share",
        "无法显示这张图片" to "Cannot display image",
        "下载" to "Download",
        "文件已不在这台手机上" to "File is no longer on this phone",
        "内容已失效" to "Content expired",
        "来源设备离线，暂时无法获取" to "Source device is offline",
        "保存到相册" to "Save to Photos",
        "无法显示这个 PDF" to "Cannot display this PDF",
        "无法播放这个视频" to "Cannot play this video",
        "播放" to "Play",
        "取消静音" to "Unmute",
        "静音" to "Mute",
        "播放进度" to "Playback progress",
        "发送到" to "Send to",
        "正在查找设备…" to "Looking for devices…",
        "请在其它设备上打开 Oink" to "Open Oink on another device",
        "Oink 正在发送" to "Oink is sending",
        "正在发送给" to "Sending to",
        "已完成" to "Completed",
        "对方不在线，请在对方设备打开 Oink\n并确保网络连接畅通" to "Peer is offline. Open Oink on it and ensure both devices are on the network",
        "输入文字，随手传输…" to "Type text to send…",

        // Settings
        "1 分钟" to "1 min",
        "5 分钟" to "5 min",
        "10 分钟" to "10 min",
        "20 分钟" to "20 min",
        "30 分钟" to "30 min",
        "60 分钟" to "60 min",
        "无限制" to "Unlimited",
        "单击扬声器按钮行为" to "Speaker button tap",
        "设为出口" to "Set as Audio Exit",
        "深浅色" to "Theme",
        "跟随系统" to "System",
        "浅色" to "Light",
        "深色" to "Dark",
        "语言" to "Language",
        "默认页面" to "Startup tab",
        "下次打开 Oink 时先显示这个页面。" to "Page shown when launching Oink.",
        "点按通知" to "Notification tap",
        "从共享剪贴板复制" to "Copy shared clipboard",
        "打开 App" to "Open App",
        "从共享剪贴板复制：把其它设备当前复制的文字或图片复制到本机；没有可复制的内容时打开 Oink。" to "Copy shared clipboard copies text/images from peers; opens Oink if empty.",
        "打开页面" to "Target tab",
        "点按通知时打开 Oink 的这个页面。" to "Page to open when tapping the notification.",
        "麦克风自动关闭" to "Mic auto-off",
        "本机作为麦克风时，如果这段时间里一直没有设备在用，就自动关闭。刚开启时从开启那一刻开始计时。" to "Automatically turns off the microphone if unused for this duration.",
        "扬声器自动关闭" to "Speaker auto-off",
        "本机作为扬声器时，如果这段时间里一直没有收到声音，就自动关闭。刚开启时从开启那一刻开始计时。" to "Automatically turns off the speaker if silent for this duration.",
        "图片" to "Images",
        "JPG、PNG、HEIC、GIF 等" to "JPG, PNG, HEIC, GIF, etc.",
        "视频" to "Videos",
        "MP4、MOV、MKV 等" to "MP4, MOV, MKV, etc.",
        "文档" to "Documents",
        "MP3、AAC、FLAC 等" to "MP3, AAC, FLAC, etc.",
        "PDF、Office、文本等" to "PDF, Office, Text, etc.",
        "压缩包" to "Archives",
        "ZIP、RAR、7Z 等" to "ZIP, RAR, 7Z, etc.",
        "安装包" to "Packages",
        "安装包（APK）" to "Packages (APK)",
        "其它类型" to "Other files",
        "其他类型" to "Other files",
        "以上之外的所有文件" to "All other file types",
        "特权模式" to "Privileged Mode",
        "开源许可" to "Open source licenses",
        "清理缓存？" to "Clear cache?",
        "清理" to "Clear",
        "清理传输记录？" to "Clear transfer history?",
        "已清理传输记录" to "Transfer history cleared",
        "将删除本机的所有传输记录，以及已下载的图片和文件。其它设备上的记录不受影响。" to "Deletes all transfer history and downloaded files on this phone. Other devices are not affected.",
        "关闭特权模式？" to "Disable Privileged Mode?",
        "特权模式已关闭" to "Privileged Mode disabled",
        "电脑将不能操控本机，自动读取剪贴板会暂停。之后可以直接重新开启，不用再配对。" to "PCs won’t control this phone and clipboard auto-read will pause. You can re-enable it anytime without pairing.",
        "重置配对凭据？" to "Reset pairing credentials?",
        "重置" to "Reset",
        "已重置，需要重新配对" to "Reset complete, re-pairing required",
        "会停止特权服务，并清除本组所有手机的 Oink 配对凭据。每台手机都需要重新配对无线调试。" to "Stops the privileged service and clears wireless debugging credentials for all phones in this group.",
        "退出后，需要重新配对才能和组内设备共享。" to "After leaving, you will need to pair again to share.",
        "本机" to "This phone",
        "设备名" to "Device name",
        "外观" to "Appearance",
        "主题色" to "Accent color",
        "首页" to "Home",
        "清理缓存会删除已下载的图片和文件，消息记录会保留。" to "Clearing cache removes downloaded images and files while keeping message history.",
        "清理传输记录" to "Clear history",
        "清理缓存" to "Clear cache",
        "正在清理…" to "Clearing…",
        "剪贴板" to "Clipboard",
        "自动写入剪贴板" to "Auto-write clipboard",
        "其它设备复制的文字会自动写入本机剪贴板" to "Text copied on peers is automatically written here",
        "自动读取剪贴板并广播" to "Auto-read & broadcast clipboard",
        "本机复制的文字会自动发到其它设备" to "Text copied on this phone is sent automatically",
        "需要先开启特权模式" to "Requires Privileged Mode",
        "去开启" to "Enable",
        "开启麦克风或扬声器后，如果一直没有设备在用，到时间会自动关闭。" to "Automatically turns off idle mic or speaker after the chosen duration.",
        "连接就绪后可修改" to "Available once connected",
        "回声消除" to "Echo cancellation",
        "消除麦克风录制的本机播放的音频" to "Removes local speaker playback picked up by the mic",
        "通知与后台" to "Notifications & background",
        "在控制中心编辑开关，可以添加 Oink 的「复制」「粘贴」「接收音频」「音频转发」「共享麦克风」。" to "Add Oink’s Copy, Paste, Receive Audio, Forward Audio, and Share Mic tiles in Quick Settings.",
        "常驻通知" to "Persistent notification",
        "在通知栏显示在线设备和音频状态" to "Shows online devices and audio status in notification shade",
        "已关闭：离开 Oink 后，Oink 将无法在后台工作" to "Off: Oink stops working when you leave the app",
        "在多任务中隐藏" to "Exclude from Recents",
        "最近任务列表里不显示 Oink" to "Hide Oink in the recent apps switcher",
        "打开方式注册" to "Open With integration",
        "在其它应用的「打开方式」里显示 Oink，用来把文件发到其它设备" to "Show Oink in other apps’ Open With menu to send files",
        "开启后，电脑可以操控本机，Oink 也能在后台读取剪贴板。需要用到无线调试。" to "Lets PCs control this phone and reads clipboard in the background via Wireless Debugging.",
        "未开启" to "Off",
        "设置中" to "Setting up",
        "正在启动" to "Starting",
        "已开启" to "Active",
        "启动失败" to "Start failed",
        "关于" to "About",
        "版本" to "Version",
        "恢复默认" to "Reset",
        "已退出设备组" to "Left device group",
        "连接服务没能启动" to "Connection service failed",
        "连接没能启动" to "Connection failed",

        // Privileged setup & recovery
        "开启开发者选项" to "Enable Developer options",
        "在「设置 › 关于手机」中，连续点按「版本号」7 次。已开启可以跳过。" to "Tap Build number 7 times in Settings › About phone. Skip if already enabled.",
        "打开关于手机" to "Open About phone",
        "打开无线调试" to "Enable Wireless debugging",
        "在「开发者选项」中打开「无线调试」。手机需要连接 Wi‑Fi。" to "Turn on Wireless debugging in Developer options while connected to Wi-Fi.",
        "打开开发者选项" to "Open Developer options",
        "输入配对码" to "Enter pairing code",
        "留在「设置」里：在「无线调试」中点按「使用配对码配对设备」，然后在 Oink 的通知里点「输入配对码」，填入系统显示的六位配对码，部分手机可能需要长按通知显示按钮。切回 Oink 会让系统关掉配对窗口；分屏同时打开「设置」和 Oink 时，也可以填在下面。" to "Stay in Settings: tap Pair device with pairing code in Wireless debugging, then reply in Oink’s notification. Returning to Oink closes the code dialog unless using split screen.",
        "分屏时在这里输入" to "Split-screen entry",
        "正在查找配对服务…" to "Searching pairing service…",
        "配对并开启" to "Pair & Enable",
        "取消设置" to "Cancel setup",
        "正在开启特权模式" to "Starting Privileged Mode",
        "通常只需要几秒钟。" to "Usually takes a few seconds.",
        "特权模式已开启" to "Privileged Mode is on",
        "电脑可以用键盘鼠标操控本机，剪贴板可以自动读取并广播。" to "PCs can control this phone and clipboard auto-read is active.",
        "重启后需先在开发者选项中打开无线调试，再回到 Oink 启动。" to "After reboot, enable Wireless debugging in Developer options and open Oink.",
        "Android 13 及以上，手机重启后会自动恢复特权模式。" to "On Android 13+, Privileged Mode restarts automatically after reboot.",
        "开机后自动恢复" to "Auto-restore after reboot",
        "重置会清除本组所有手机的配对凭据，之后需要重新配对。系统「无线调试 › 已配对的设备」中的旧 Oink 记录需要手动删除。" to "Reset clears pairing credentials for all phones in this group. Remove old Oink entries manually in Wireless debugging › Paired devices.",
        "关闭特权模式" to "Disable Privileged Mode",
        "重置配对凭据" to "Reset pairing credentials",
        "特权模式启动失败" to "Privileged Mode failed to start",
        "无法连接无线调试。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "Could not connect to Wireless debugging. Ensure Wi-Fi and Wireless debugging are enabled.",
        "重新配对" to "Re-pair",
        "特权模式未开启" to "Privileged Mode is off",
        "开启后，电脑可以用键盘鼠标操控本机；Oink 可以在后台读取剪贴板并自动广播，控制中心的「粘贴」不用打开 Oink 就能发送文字。" to "Enables PC keyboard/mouse control over this phone and background clipboard reading.",
        "开始设置" to "Start setup",
        "会改动的系统设置" to "System settings modified",
        "也可以把手机用 USB 连到电脑，在电脑上的 Oink 中开启。" to "Or connect via USB and enable from Oink on your PC.",
        "开发者选项" to "Developer options",
        "需要先开启" to "Must be enabled first",
        "无线调试" to "Wireless debugging",
        "用于启动特权服务，需要 Wi‑Fi" to "Used to start privileged service over Wi-Fi",
        "ADB 授权超时" to "Disable ADB authorization timeout",
        "会被关闭，以便重启后自动恢复" to "Disabled so auto-restore works after reboot",
        "系统没有允许 Oink 使用无线调试。" to "Android denied Wireless debugging access.",
        "无线调试的配对已失效，请重新配对。" to "Wireless debugging pairing expired, please re-pair.",
        "特权服务没能启动，请重试。" to "Privileged service failed to start, please retry.",
        "无线调试不可用。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "Wireless debugging unavailable. Ensure Wi-Fi and Wireless debugging are on.",
        "无法连接无线调试。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "Cannot reach Wireless debugging. Ensure Wi-Fi and Wireless debugging are on.",
        "Oink 使用了以上开源项目，感谢它们的作者。" to "Oink uses the following open source projects. Thanks to their authors.",
        "无法打开系统设置" to "Could not open System Settings",
        "无法重置配对凭据" to "Could not reset pairing credentials",
        "分享已中断，请重新分享" to "Share interrupted, please try again",
        "特权恢复" to "Privileged recovery",
        "Oink 正在恢复特权连接" to "Oink is restoring privileged connection",
        "等待可信无线网络和无线调试服务" to "Waiting for trusted Wi-Fi and Wireless debugging",
        "配对码是六位数字，请重新输入。" to "Pairing code must be 6 digits, try again.",
        "特权模式配对" to "Privileged pairing",
        "六位配对码" to "6-digit code",
        "刚才的配对码没有成功。请输入「使用配对码配对设备」显示的新配对码，不用离开「设置」。" to "Previous code failed. Enter the new 6-digit code shown in Pair device with pairing code without leaving Settings.",
        "请输入「使用配对码配对设备」显示的六位配对码，不用离开「设置」。" to "Enter the 6-digit code from Pair device with pairing code without leaving Settings.",
        "重新输入配对码" to "Re-enter pairing code",
        "已找到配对服务" to "Pairing service found",
        "正在配对…" to "Pairing…",
        "正在开启特权模式。" to "Starting Privileged Mode.",
        "系统的配对码已失效：离开「设置」时系统会关闭配对码对话框。请在「无线调试」中重新点按「使用配对码配对设备」，不要离开「设置」，在 Oink 的通知里输入新的配对码。" to "System pairing code expired after leaving Settings. Re-open Pair device with pairing code and reply via Oink’s notification.",
        "配对码不正确或已过期。请在「无线调试」中重新点按「使用配对码配对设备」，在 Oink 的通知里输入新的配对码。" to "Incorrect or expired pairing code. Request a new code in Wireless debugging and reply via notification.",
        "无法完成配对，请重试。" to "Pairing failed, please retry.",

        // Themes & Control page
        "粉色" to "Pink",
        "橙色" to "Orange",
        "紫色" to "Purple",
        "蓝色" to "Blue",
        "青色" to "Teal",
        "请先选择要控制的电脑" to "Select a computer to control first",
        "调节" to "Adjust",
        "全屏" to "Full screen",
        "收起键盘" to "Hide keyboard",
        "弹出键盘" to "Show keyboard",
        "恢复" to "Restore",
        "切换键盘：换到模拟键盘（发送原始键码）" to "Switch to emulated keyboard",
        "切换键盘：换到系统键盘（手机输入法）" to "Switch to system keyboard",
        "选择设备" to "Select device",
        "指针未锁定" to "Pointer unlocked",
        "指针已锁定" to "Pointer locked",
        "游戏模式已开启" to "Game Mode on",
        "游戏模式已开启（自动）" to "Game Mode on (auto)",
        "更多" to "More",
        "键盘布局" to "Keyboard layout",
        "单指划动" to "1-finger swipe",
        "键盘自动弹出" to "Auto keyboard",
        "指针速度" to "Pointer speed",
        "慢" to "Slow",
        "快" to "Fast",
        "可控制的在线设备" to "Controllable online devices",
        "解锁指针" to "Unlock pointer",
        "锁定指针" to "Lock pointer",
        "触摸板" to "Touchpad",
        "触摸移动" to "Touch to move",
        "轻触单击" to "Tap to click",
        "手指已锁定" to "Finger locked",
        "单击" to "Click",
        "双击" to "Double click",
        "右键" to "Right click",
        "释放：抬起锁定的手指" to "Release: lift locked finger",
        "释放" to "Release",
        "拖动键盘" to "Drag keyboard",
        "已按下" to "Pressed",
        "未按下" to "Released",
        "展开全屏键盘" to "Expand keyboard",
        "切换扬声器模式" to "Switch speaker mode",
        "快退 / 上一首" to "Rewind / Previous",
        "播放 / 暂停" to "Play / Pause",
        "快进 / 下一首" to "Fast-forward / Next",
        "音量-" to "Vol -",
        "音量+" to "Vol +",
        "向前删除" to "Forward delete",
        "还原：回到浮动键盘" to "Restore floating keyboard",
        "退格" to "Backspace",
        "回车" to "Enter",
        "空格" to "Space",
        "左" to "Left",
        "上" to "Up",
        "下" to "Down",
        "右" to "Right",
        "按住" to "Held",
        "锁定" to "Locked",
        "解锁" to "Unlock",
        "字母" to "ABC",
        "符号" to "Symbols",
        "移动指针" to "Move pointer",
        "滚动" to "Scroll",
        "美式英语（US）" to "English (US)",
        "英式英语（UK）" to "English (UK)",
        "德语（QWERTZ）" to "German (QWERTZ)",
        "法语（AZERTY）" to "French (AZERTY)",
        "西班牙语" to "Spanish",
        "日语（JIS）" to "Japanese (JIS)",
        "韩语" to "Korean",
        "输入到焦点电脑" to "Type to focused PC",

        // Control Overlay (Gamepads & Button Mapping)
        "按键映射" to "Button mapping",
        "编辑按键" to "Edit buttons",
        "添加按键" to "Add button",
        "配置方案" to "Profiles",
        "完成" to "Done",
        "绑定按键" to "Bind key",
        "形状" to "Shape",
        "圆形" to "Circle",
        "胶囊" to "Pill",
        "大小" to "Size",
        "触发方式" to "Mode",
        "按住触发" to "Hold",
        "切换开关" to "Toggle",
        "文字标签" to "Label",
        "重命名" to "Rename",
        "新建方案" to "New profile",
        "透明度" to "Opacity",
        "选择要绑定的键位或鼠标按键" to "Choose a keyboard or mouse action",
        "鼠标按键" to "Mouse buttons",
        "常用按键" to "Common keys",
        "字母键" to "Letters",
        "数字键" to "Numbers",
        "方向键" to "Arrow keys",
        "功能键" to "Function keys",
        "左键" to "Left Click",
        "中键" to "Middle Click",
        "方案名称" to "Profile name",
        "关闭按键映射" to "Close overlay",

        // Pairing errors (PairingText.kt)
        "请输入有效的 IPv4 地址" to "Enter a valid IPv4 address",
        "不能与当前设备配对" to "Cannot pair with this device",
        "目标主机不可达" to "Host unreachable",
        "连接被拒绝" to "Connection refused",
        "连接超时" to "Connection timed out",
        "该地址上没有 Oink 设备" to "No Oink device at this address",
        "设备身份已变化，请重新搜索" to "Device identity changed, search again",
        "对方的协议版本不兼容" to "Incompatible protocol version",
        "对方不支持所需功能" to "Peer lacks required capability",
        "设备正忙，请稍后重试" to "Device busy, try again later",
        "操作过于频繁，请稍后重试" to "Too many attempts, try again later",
        "对方拒绝了配对" to "Peer declined pairing",
        "对方取消了配对" to "Peer cancelled pairing",
        "已取消配对" to "Pairing cancelled",
        "对方关闭了配对" to "Peer closed pairing",
        "连接已断开" to "Connection lost",
        "请输入六位配对码" to "Enter 6-digit pairing code",
        "配对码不正确，请重新输入" to "Incorrect code, try again",
        "尝试次数已用完" to "No attempts left",
        "此配对已结束" to "Pairing ended",
        "设备已经连接" to "Already paired",
        "已由另一配对会话接替" to "Replaced by another pairing session",
        "设备组已变化" to "Device group changed",
        "设备数量已达上限" to "Device limit reached",
        "设备组数据超过限制" to "Group data limit exceeded",
        "无法验证设备组成员" to "Could not verify group membership",
        "设备组合并冲突" to "Group merge conflict",
        "无法保存配对结果" to "Could not save pairing result",
        "无法确认提交结果" to "Could not confirm commit",
        "无法获取合并证明" to "Merge proof unavailable",
        "设备成员资格已撤销" to "Membership revoked",
        "设备组已变化，无法撤销" to "Group changed, cannot undo",
        "不允许此操作" to "Operation not permitted",
        "搜索结果的连接已断开，请重新搜索" to "Prepared connection lost, search again",
        "配对状态已变化，请重试" to "Pairing state changed, retry",
        "配对协议异常" to "Pairing protocol error",
        "设备状态已变化，请重新搜索" to "Device state changed, search again",
        "配对已经提交" to "Pairing already committed",
        "无法验证设备身份" to "Could not verify device identity",
        "没有共同支持的加密方式" to "No common encryption supported",
        "不支持该屏幕排列" to "Unsupported display layout",
        "配对服务不可用" to "Pairing service unavailable",
        "网络操作超时" to "Network operation timed out",
        "对方还有没处理的配对请求，请稍后重试" to "Peer has a pending request, try again later",
        "配对状态未知" to "Unknown pairing state",
        "本机没有连接网络" to "No network connection",

        // RustCoreBridge & RustDeviceCards
        "核心事件处理失败" to "Core event handling failed",
        "设置已生效" to "Setting applied",
        "设置未变化" to "No change",
        "设置等待确认" to "Pending confirmation",
        "地址查询已失效，请重新查找" to "Address lookup expired, search again",
        "配对已完成" to "Pairing completed",
        "配对提交结果待确认，请检查设备列表" to "Pairing unconfirmed, check Devices",
        "未知设置" to "Unknown setting",
        "无法识别图片格式" to "Unrecognized image format",
        "图片太大，无法复制" to "Image too large to copy",
        "正在连接对方…" to "Connecting to peer…",
        "正在验证并合并设备组…" to "Verifying and merging group…",
        "等待对方接受并输入验证码" to "Waiting for peer to accept & enter code",
        "请输入对方显示的六位码" to "Enter 6-digit code shown on peer",
        "对方的状态已变化，请重试" to "Peer state changed, please retry",
        "设置值无效" to "Invalid setting value",
        "对方无法保存设置" to "Peer could not save setting",
        "对方无法应用设置" to "Peer could not apply setting",
        "不确定是否已生效，请检查对方状态" to "Unconfirmed, please check peer state",
        "该设备不是音频出口" to "Device is not audio exit",
        "音频线路已变化，请重试" to "Audio route changed, retry",
        "音频输出设备已变化，请重试" to "Audio output changed, retry",
        "对方没有可用的音频输出" to "No audio output on peer",
        "对方不支持调节音量" to "Volume control unsupported",
        "对方没有授予所需权限" to "Peer missing required permission",
        "已被新的设置取代" to "Superseded by newer setting",
        "对方的状态有冲突，请重试" to "Peer state conflict, retry",
        "未知原因" to "Unknown reason",
        "扬声器转发音量" to "Speaker forwarding volume",
        "麦克风转发音量" to "Mic forwarding volume",
        "恢复此设备" to "Resume device",
        "暂停此设备" to "Pause device",
        "共享屏幕" to "Share screen",
        "屏幕仅本机" to "Screen local only",
        "共享鼠标" to "Share mouse",
        "鼠标仅本机" to "Mouse local only",
        "禁用鼠标" to "Disable mouse",
        "共享键盘" to "Share keyboard",
        "键盘仅本机" to "Keyboard local only",
        "禁用键盘" to "Disable keyboard",
        "启用键盘" to "Enable keyboard",
        "剪贴板仅本机" to "Clipboard local only",
        "共享剪贴板" to "Share clipboard",
        "启用音频转发" to "Enable audio forwarding",
        "关闭音频转发" to "Disable audio forwarding",
        "取消音频出口" to "Clear audio exit",
        "设为音频出口" to "Set as audio exit",
        "麦克风仅本机" to "Microphone local only",
        "接收麦克风" to "Receive microphone",
        "游戏模式自动" to "Game Mode auto",
        "开启游戏模式" to "Game Mode on",
        "关闭游戏模式" to "Game Mode off",
        "解除指针锁定" to "Unlock pointer",
        "锁定指针到此设备" to "Lock pointer here",
        "屏幕" to "Screen",
        "输出音量" to "Output volume",
        "设置被拒绝" to "Setting declined",
        "设置失败" to "Setting failed",
        "对端缺少能力" to "Peer lacks capability",
        "对端协议不兼容" to "Peer protocol incompatible",
        "无法保存到相册" to "Could not save to Photos",
        "停止设备广播失败" to "Failed to stop device advertising",
        "局域网发现失败" to "LAN discovery failed",
        "停止局域网发现失败" to "Failed to stop LAN discovery",
        "解析附近设备失败" to "Failed to resolve nearby device",
        "订阅附近设备失败" to "Failed to subscribe to nearby device",
        "无法分享" to "Could not share",
        "对方设备" to "Peer device",
        "对方不在线" to "Peer is offline",
        "所有在线设备" to "All online devices",
        "此内容请选一台电脑" to "Select a computer for this",
        "没有其它设备在线" to "No other devices online",
        "下一项" to "Next item",
        "上一项" to "Previous item",
        "展开" to "Expand",
        "文字" to "Text",
        "音频转发" to "Forward audio",
        "分享内容为空或不受支持" to "Shared content is empty or unsupported",
        "只能读取获授权的内容 URI" to "Can only read authorized content URIs",
        "无法创建暂存目录" to "Could not create staging directory",
        "无法保存照片" to "Could not save photo",
        "无法读取文件夹" to "Could not read folder",
        "文件夹内容还在加载，请稍后重新选择" to "Folder is still loading, please pick again shortly",
        "还没有下载完成" to "Download not finished yet",
        "无法打开" to "Could not open",
        "此设备未提供状态" to "No status reported by this device",
    )

    internal val JA_MAP: Map<String, String> = mapOf(
        "设备" to "デバイス",
        "传输" to "転送",
        "控制" to "操作",
        "设置" to "設定",
        "共享剪贴板" to "共有クリップボード",
        "添加设备" to "デバイスを追加",
        "本机信息" to "このデバイスの情報",
        "打开设置" to "設定を開く",
        "关闭设置" to "設定を閉じる",
        "返回" to "戻る",
        "关闭" to "閉じる",
        "取消" to "キャンセル",
        "确认" to "確認",
        "保存" to "保存",
        "知道了" to "OK",
        "重试" to "再試行",
        "查找" to "検索",
        "配对" to "ペアリング",
        "取消配对" to "ペアリングを中止",
        "退出" to "終了",
        "退出 Oink" to "Oink を終了",
        "退出设备组" to "グループから退出",

        "电脑连接" to "PC 接続",
        "打开 Oink" to "Oink を開く",
        "复制内容" to "共有内容をコピー",
        "停止接收音频" to "音声受信を停止",
        "停止转发音频" to "音声転送を停止",
        "停止共享麦克风" to "マイク共有を停止",
        "Oink · 点按从共享剪贴板复制" to "Oink · タップで共有クリップボードをコピー",
        "Oink · 运行中" to "Oink · 実行中",
        "已复制" to "コピーしました",
        "转发音频" to "音声転送",
        "音频出口" to "音声出力",
        "麦克风共享" to "マイク共有中",
        "复制" to "コピー",
        "粘贴" to "ペースト",
        "接收音频" to "音声を受信",
        "共享麦克风" to "マイクを共有",
        "Oink 未运行" to "Oink 未起動",
        "未配对" to "未ペアリング",
        "需特权模式" to "特権モードが必要",
        "转发中" to "転送中",
        "获取中" to "取得中",
        "没有内容" to "内容なし",
        "复制失败" to "コピー失敗",
        "发送中" to "送信中",
        "已发送" to "送信済み",
        "发送失败" to "送信失敗",
        "收到的文件" to "受信したファイル",
        "发送进度" to "送信状況",

        "正在连接…" to "接続中…",
        "已连接局域网" to "LAN に接続済み",
        "网络出错，正在重新连接…" to "ネットワークエラー、再接続中…",
        "连接离线" to "オフライン",
        "网络绑定失败，使用系统默认网络" to "ネットワーク固定に失敗、既定を使用",
        "局域网发现启动失败" to "LAN 検出の開始に失敗",
        "停止广播失败" to "ブロードキャスト停止失敗",
        "设备广播失败" to "デバイス通知失敗",
        "无法读取传输设置" to "転送設定を読み込めません",
        "无法读取设备组" to "グループを読み込めません",
        "无法离开设备组" to "グループから退出できません",
        "本机 · 离线" to "このデバイス · オフライン",
        "本机" to "このデバイス",
        "离线" to "オフライン",
        "在线" to "オンライン",
        "正在连接" to "接続中",
        "选择期间 Oink 被系统关闭，请重新选择" to "選択中にアプリが終了しました。もう一度お選びください",
        "Rust 核心尚未安装，请先生成移动绑定" to "Rust コアが読み込まれていません",
        "无法读取音频设置" to "音声設定を読み込めません",
        "传输失败，请重试" to "転送に失敗しました。再試行してください",
        "下载失败，请重试" to "ダウンロードに失敗しました。再試行してください",
        "发现结果更新失败" to "検出結果の更新に失敗しました",
        "发现结果撤回失败" to "検出結果の取り消しに失敗しました",
        "剪贴板历史同步失败" to "クリップボード履歴の同期に失敗しました",
        "连接尚未就绪，请稍候" to "接続の準備中です。しばらくお待ちください",
        "操作失败" to "操作に失敗しました",
        "音频出口音量暂不可调" to "現在この出力音量は調整できません",
        "无法修改设备名" to "デバイス名を変更できません",
        "无法修改自动接收设置" to "自動受信設定を変更できません",
        "转发音频仅在特权模式可用" to "音声転送は特権モードでのみ利用可能です",
        "请在 Oink 中允许使用麦克风" to "Oink でマイク権限を許可してください",
        "没有麦克风权限，无法开启麦克风" to "マイク権限がないためオンにできません",
        "无法统计缓存" to "キャッシュ容量を計算できません",
        "无法读取传输记录" to "転送履歴を読み込めません",
        "无法删除消息" to "メッセージを削除できません",
        "无法清理传输缓存" to "キャッシュを消去できません",
        "无法清理传输记录" to "履歴を消去できません",
        "下载任务暂不可用" to "現在ダウンロードできません",
        "请稍后重试" to "後ほど再試行してください",
        "无法打开文件" to "ファイルを開けません",
        "此设备的麦克风音量暂不可调" to "このデバイスのマイク音量は調整できません",
        "此设备的扬声器音量暂不可调" to "このデバイスのスピーカー音量は調整できません",
        "没有可用的应用" to "対応するアプリがありません",
        "此设备暂时无法暂停" to "現在このデバイスを一時停止できません",
        "无法修改 IP" to "IP を更新できません",
        "未知错误" to "不明なエラー",
        "未知" to "不明",
        "无法创建相册项目" to "メディア項目を作成できません",
        "已保存到相册" to "写真に保存しました",
        "没有可复制的内容" to "コピーできる内容がありません",
        "剪贴板是空的" to "クリップボードが空です",
        "剪贴板里的内容不能发送" to "クリップボードの内容は送信できません",
        "分享收到的内容" to "受信した内容を共有",
        "Oink 没有在运行" to "Oink は実行されていません",
        "与电脑的连接中断，请重试" to "PC との接続が切れました。再試行してください",
        "键鼠共享已暂停，输入没有发出" to "入力共有が一時停止中のため送信されません",
        "这台设备不支持这项操作" to "このデバイスはこの操作に対応していません",
        "焦点电脑已暂停共享，文字没有输入" to "操作対象の PC が共有を一時停止しています",
        "焦点电脑没有加入键鼠共享，文字没有输入" to "操作対象の PC が入力共有に参加していません",
        "这台设备不能输入文字" to "このデバイスには文字を入力できません",
        "转发音频失败：系统音频正被其它功能占用（如投屏）" to "音声転送失敗：システム音声が他の機能で使用中です",
        "请解锁手机后再开始转发" to "画面ロックを解除してから開始してください",
        "转发音频已中断，请重试" to "音声転送が中断されました。再試行してください",
        "调节本机音量不影响转发" to "端末の音量を変更しても転送には影響しません",
        "转发中本机静音" to "転送中は本機スピーカーを消音",
        "无法复制内容" to "内容をコピーできません",
        "无法写入手机剪贴板" to "クリップボードに書き込めません",
        "接收内容已过期，请重新获取" to "有効期限が切れました。再試行してください",
        "已接收并复制到手机剪贴板" to "受信してクリップボードにコピーしました",
        "正在准备分享…" to "共有を準備中…",
        "正在读取内容，请完成后重新选择" to "読み込み中です。完了後にもう一度お選びください",
        "正在发送，请完成后重新分享" to "送信中です。完了後にもう一度お試しください",
        "已取消发送" to "送信を中止しました",
        "已取消" to "キャンセル済み",

        "在线电脑没有可获取的剪贴板内容。请先在电脑上复制内容，再重试。" to "オンラインの PC から取得できるクリップボードがありません。PC でコピーしてから再試行してください。",
        "连接中断，请重试" to "接続が切れました。再試行してください",
        "没有在线的电脑" to "オンラインの PC がありません",
        "来源设备已离线，上线后再试" to "送信元デバイスがオフラインです",
        "来源设备已不再保留此内容" to "送信元デバイスにこの項目は残っていません",
        "来源设备上已没有此内容" to "送信元デバイスにこの内容がありません",
        "此内容已被来源设备上的新内容替换" to "送信元の新しい内容で上書きされました",
        "来源设备无法提供此内容" to "送信元から提供できません",
        "共享内容已过期" to "共有内容の期限が切れました",
        "这条消息已不存在" to "このメッセージは存在しません",
        "本机发送的内容没有可下载的来源" to "このデバイスが送信した項目はダウンロード不要です",
        "正在下载中" to "ダウンロード中",
        "手机存储空间不足" to "ストレージ容量が不足しています",
        "无法读取要发送的内容" to "送信する内容を読み取れません",
        "存储空间不足或无法写入" to "容量不足または書き込み失敗",
        "对方版本不兼容" to "相手のバージョンに互換性がありません",
        "对方已暂停共享" to "相手が共有を一時停止しています",
        "对方关了同步发送" to "相手が同期送信をオフにしています",
        "文件夹内容已失效" to "フォルダ内容は失効しました",
        "无法获取文件夹内容" to "フォルダを取得できません",
        "没有在线的设备" to "オンラインのデバイスがありません",
        "对方没有响应" to "応答がありません",
        "对方拒绝接收" to "相手が受信を拒否しました",
        "对方无法接收" to "相手が受信できません",
        "连接中断" to "接続が中断されました",
        "对方已断开" to "相手が切断されました",
        "对方已断开，发送失败" to "切断されたため送信失敗",
        "对方已断开，接收失败" to "切断されたため受信失敗",
        "设备不在线" to "デバイスはオフラインです",
        "对方的 Oink 版本不支持此操作" to "相手の Oink バージョンが非対応です",
        "对方的 Oink 版本不支持此设置" to "相手の Oink バージョンがこの設定に非対応です",
        "还没有收到对方的状态，请稍候" to "相手の状態を待っています",
        "对方的状态已过期，请稍候" to "相手の状態が古くなっています",
        "对方设备出现故障" to "相手デバイスで障害が発生しました",
        "该设备不在设备组中" to "グループに含まれていないデバイスです",
        "对方正忙，请稍后再试" to "相手がビジー状態です",
        "此操作暂不可用" to "この操作は現在利用できません",
        "请先在电脑上授予 Oink 所需权限" to "先に PC で Oink に必要な権限を許可してください",
        "IP 地址格式不正确" to "IP アドレスの形式が正しくありません",
        "无法保存，请重试" to "保存できませんでした。再試行してください",
        "不能屏蔽本机" to "このデバイスはブロックできません",
        "无法保存屏蔽列表，请重试" to "ブロックリストを保存できませんでした",
        "屏蔽列表操作失败" to "ブロックリストの操作に失敗しました",
        "正在获取…" to "取得中…",
        "获取失败" to "取得に失敗",
        "版本过低，请升级" to "バージョンが古すぎます。更新してください",
        "上次在线" to "前回のオンライン",
        "很久" to "ずっと",

        "还没有其它设备" to "他のデバイスがありません",
        "添加一台电脑或手机，就能共享键鼠、剪贴板、文件和音频。" to "PC やスマートフォンを追加すると、マウス・キーボード・クリップボード・ファイル・音声を共有できます。",
        "Android，特权模式已开启" to "Android、特権モード有効",
        "鼠标" to "マウス",
        "键盘" to "キーボード",
        "音频" to "オーディオ",
        "麦克风" to "マイク",
        "扬声器" to "スピーカー",
        "共享" to "共有",
        "本地" to "ローカル",
        "禁用" to "無効",
        "已禁用" to "無効化済み",
        "指针锁定" to "ポインタ固定",
        "开启" to "オン",
        "关闭" to "オフ",
        "游戏模式" to "ゲームモード",
        "自动" to "自動",
        "转发到出口" to "出力先に転送",
        "作为出口" to "出力先にする",
        "转发" to "転送",
        "接收" to "受信",
        "发送" to "送信",
        "开" to "オン",
        "关" to "オフ",
        "游戏模式：自动" to "ゲームモード：自動",
        "已暂停" to "一時停止中",
        "已继续" to "再開しました",
        "继续" to "再開",
        "暂停" to "一時停止",
        "踢出设备" to "デバイスを削除",
        "无法远程控制此设备" to "このデバイスは遠隔操作できません",
        "编辑 IP" to "IP を編集",
        "踢出后，需要重新配对才能再次和它共享" to "削除後は再度ペアリングが必要です",
        "踢出" to "削除",
        "IP 已更新" to "IP を更新しました",
        "IP 地址" to "IP アドレス",
        "正在保存…" to "保存中…",
        "请输入 IP 地址" to "IP アドレスを入力してください",
        "请输入有效的 IP 地址" to "有効な IP アドレスを入力してください",
        "端口需为 1–65535，或留空" to "ポートは 1〜65535 または空欄",
        "本地，音量固定为零" to "ローカル（音量 0 固定）",

        "配对请求" to "ペアリングリクエスト",
        "附近设备" to "近くのデバイス",
        "刷新附近设备" to "近くのデバイスを更新",
        "协议版本不兼容" to "プロトコルバージョン非互換",
        "正在搜索附近的设备" to "近くのデバイスを検索中",
        "手动添加" to "手動で追加",
        "请确认对方已打开 Oink，并和本机连接到同一个网络。" to "相手デバイスで Oink が起動しており、同じネットワークにあるか確認してください。",
        "本机 IP" to "ローカル IP",
        "本机端口" to "ローカルポート",
        "本机 ID" to "デバイス ID",
        "点按任一项即可复制" to "項目をタップしてコピー",
        "请输入有效的 IPv4 地址，例如 192.168.1.20" to "有効な IPv4 アドレスを入力してください（例：192.168.1.20）",
        "端口应在 1–65535 之间" to "ポートは 1〜65535 で指定してください",
        "输入对方的 IP 地址。对方的地址显示在它「添加设备」页的底部。" to "相手の「デバイスを追加」画面に表示されている IP アドレスを入力してください。",
        "端口" to "ポート",
        "正在查找…" to "検索中…",
        "配对失败" to "ペアリング失敗",
        "配对成功" to "ペアリング成功",
        "等待对方输入" to "相手の入力を待機中",
        "正在验证…" to "検証中…",
        "配对后，对方可以和本机共享剪贴板、文件和音频；开启特权模式后还能操控本机。请确认你信任此设备。" to "ペアリングするとクリップボード・ファイル・音声を共有でき、特権モード時は本機の操作も可能になります。信頼できるデバイスのみ許可してください。",

        "更早的记录仅保存在本机" to "これより前の履歴はこの端末のみに保存",
        "加载失败" to "読み込み失敗",
        "加载更早的记录" to "以前の履歴を読み込む",
        "正在加载更早的记录…" to "以前の履歴を読み込み中…",
        "已显示最近 100 条共享记录" to "最新 100 件の共有履歴を表示中",
        "更早的本机记录" to "以前のローカル履歴",
        "隐藏" to "非表示",
        "隐藏内容" to "内容を隠す",
        "收起" to "折りたたむ",
        "收起为一叠" to "スタックにまとめる",
        "未选择" to "未選択",
        "添加附件" to "添付を追加",
        "当前活跃" to "アクティブデバイス",
        "电脑" to "コンピューター",
        "移动设备" to "モバイルデバイス",
        "删除这条消息？" to "このメッセージを削除しますか？",
        "删除" to "削除",
        "删除后无法恢复。" to "削除後は元に戻せません。",
        "已下载的文件会一起删除，删除后无法恢复。" to "ダウンロード済みファイルも削除され、元に戻せません。",
        "拍摄" to "カメラ",
        "图库" to "アルバム",
        "文件" to "ファイル",
        "文件夹" to "フォルダ",
        "取消发送" to "送信をキャンセル",
        "取消接收" to "受信をキャンセル",
        "复制文本" to "テキストをコピー",
        "选择" to "選択",
        "正在发送…" to "送信中…",
        "经" to "経由",
        "已隐藏的内容" to "非表示の内容",
        "查看更多" to "もっと見る",
        "显示" to "表示",
        "选择文字" to "文字を選択",
        "图片已失效" to "画像は失効しました",
        "图片暂时无法加载" to "画像を読み込めません",
        "打开图片" to "画像を開く",
        "接收失败" to "受信失敗",
        "暂时无法加载" to "現在読み込めません",
        "加载失败，点按重试" to "失敗、タップして再試行",
        "已失效" to "失効",
        "下载失败" to "ダウンロード失敗",
        "用其它应用打开" to "他のアプリで開く",
        "分享" to "共有",
        "无法显示这张图片" to "画像を表示できません",
        "下载" to "ダウンロード",
        "文件已不在这台手机上" to "ファイルはこの端末にありません",
        "内容已失效" to "内容は失効しました",
        "来源设备离线，暂时无法获取" to "送信元がオフラインのため取得できません",
        "保存到相册" to "写真に保存",
        "无法显示这个 PDF" to "PDF を表示できません",
        "无法播放这个视频" to "動画を再生できません",
        "播放" to "再生",
        "取消静音" to "ミュート解除",
        "静音" to "ミュート",
        "播放进度" to "再生位置",
        "发送到" to "送信先",
        "正在查找设备…" to "デバイスを検索中…",
        "请在其它设备上打开 Oink" to "他のデバイスで Oink を開いてください",
        "Oink 正在发送" to "Oink で送信中",
        "正在发送给" to "送信先：",
        "已完成" to "完了",
        "对方不在线，请在对方设备打开 Oink\n并确保网络连接畅通" to "相手がオフラインです。相手デバイスで Oink を開き、同じネットワークに接続してください",
        "输入文字，随手传输…" to "テキストを入力して送信…",

        "1 分钟" to "1 分",
        "5 分钟" to "5 分",
        "10 分钟" to "10 分",
        "20 分钟" to "20 分",
        "30 分钟" to "30 分",
        "60 分钟" to "60 分",
        "无限制" to "無制限",
        "单击扬声器按钮行为" to "スピーカーボタン動作",
        "设为出口" to "出力先に設定",
        "深浅色" to "外観モード",
        "跟随系统" to "システムに合わせる",
        "浅色" to "ライト",
        "深色" to "ダーク",
        "语言" to "言語",
        "默认页面" to "起動時のタブ",
        "下次打开 Oink 时先显示这个页面。" to "次回 Oink を開いたときに最初に表示するページです。",
        "点按通知" to "通知タップ時の動作",
        "从共享剪贴板复制" to "共有クリップボードをコピー",
        "打开 App" to "アプリを開く",
        "从共享剪贴板复制：把其它设备当前复制的文字或图片复制到本机；没有可复制的内容时打开 Oink。" to "他のデバイスがコピーした文字や画像をコピーします。何もない場合は Oink を開きます。",
        "打开页面" to "開くページ",
        "点按通知时打开 Oink 的这个页面。" to "通知をタップしたときに開くページです。",
        "麦克风自动关闭" to "マイク自動オフ",
        "本机作为麦克风时，如果这段时间里一直没有设备在用，就自动关闭。刚开启时从开启那一刻开始计时。" to "この端末のマイクが使われない状態が続くと自動的にオフにします。",
        "扬声器自动关闭" to "スピーカー自動オフ",
        "本机作为扬声器时，如果这段时间里一直没有收到声音，就自动关闭。刚开启时从开启那一刻开始计时。" to "音声の受信がない状態が続くと自動的にオフにします。",
        "图片" to "画像",
        "JPG、PNG、HEIC、GIF 等" to "JPG、PNG、HEIC、GIF など",
        "视频" to "動画",
        "MP4、MOV、MKV 等" to "MP4、MOV、MKV など",
        "文档" to "ドキュメント",
        "MP3、AAC、FLAC 等" to "MP3、AAC、FLAC など",
        "PDF、Office、文本等" to "PDF、Office、テキストなど",
        "压缩包" to "アーカイブ",
        "ZIP、RAR、7Z 等" to "ZIP、RAR、7Z など",
        "安装包" to "パッケージ",
        "安装包（APK）" to "パッケージ (APK)",
        "其它类型" to "その他",
        "其他类型" to "その他",
        "以上之外的所有文件" to "上記以外のすべてのファイル",
        "特权模式" to "特権モード",
        "开源许可" to "オープンソースライセンス",
        "清理缓存？" to "キャッシュを消去しますか？",
        "清理" to "消去",
        "清理传输记录？" to "転送履歴を消去しますか？",
        "已清理传输记录" to "転送履歴を消去しました",
        "将删除本机的所有传输记录，以及已下载的图片和文件。其它设备上的记录不受影响。" to "この端末上のすべての転送履歴とダウンロード済みファイルを削除します。他のデバイスには影響しません。",
        "关闭特权模式？" to "特権モードをオフにしますか？",
        "特权模式已关闭" to "特権モードをオフにしました",
        "电脑将不能操控本机，自动读取剪贴板会暂停。之后可以直接重新开启，不用再配对。" to "PC から操作できなくなり、クリップボード自動読み取りも一時停止します。再ペアリング不要でいつでも再開できます。",
        "重置配对凭据？" to "ペアリング資格情報をリセットしますか？",
        "重置" to "リセット",
        "已重置，需要重新配对" to "リセットしました。再ペアリングが必要です",
        "会停止特权服务，并清除本组所有手机的 Oink 配对凭据。每台手机都需要重新配对无线调试。" to "特権サービスを停止し、グループ内のすべての端末のワイヤレスデバッグ資格情報を消去します。",
        "退出后，需要重新配对才能和组内设备共享。" to "退出後に再び共有するにはペアリングし直す必要があります。",
        "本机" to "この端末",
        "设备名" to "デバイス名",
        "外观" to "外観",
        "主题色" to "アクセントカラー",
        "首页" to "ホーム",
        "清理缓存会删除已下载的图片和文件，消息记录会保留。" to "ダウンロード済みの画像とファイルを削除します。メッセージ履歴は残ります。",
        "清理传输记录" to "転送履歴を消去",
        "清理缓存" to "キャッシュを消去",
        "正在清理…" to "消去中…",
        "剪贴板" to "クリップボード",
        "自动写入剪贴板" to "クリップボード自動書き込み",
        "其它设备复制的文字会自动写入本机剪贴板" to "他のデバイスでコピーした文字を自動的に書き込みます",
        "自动读取剪贴板并广播" to "クリップボード自動読み取り・共有",
        "本机复制的文字会自动发到其它设备" to "ここでコピーした文字を他のデバイスへ自動送信します",
        "需要先开启特权模式" to "特権モードが必要です",
        "去开启" to "有効にする",
        "开启麦克风或扬声器后，如果一直没有设备在用，到时间会自动关闭。" to "無通信が続いた場合、設定時間で自動的にオフにします。",
        "连接就绪后可修改" to "接続後に変更可能",
        "回声消除" to "エコーキャンセル",
        "消除麦克风录制的本机播放的音频" to "マイクに入る本機の再生音を除去します",
        "通知与后台" to "通知とバックグラウンド",
        "在控制中心编辑开关，可以添加 Oink 的「复制」「粘贴」「接收音频」「音频转发」「共享麦克风」。" to "クイック設定パネルから Oink の「コピー」「ペースト」「音声受信」「音声転送」「マイク共有」タイルを追加できます。",
        "常驻通知" to "常駐通知",
        "在通知栏显示在线设备和音频状态" to "オンラインデバイスと音声状態を通知に表示します",
        "已关闭：离开 Oink 后，Oink 将无法在后台工作" to "オフ：アプリを離れるとバックグラウンドで動作しません",
        "在多任务中隐藏" to "タスク一覧から隠す",
        "最近任务列表里不显示 Oink" to "最近使ったアプリ一覧に表示しません",
        "打开方式注册" to "「アプリで開く」に登録",
        "在其它应用的「打开方式」里显示 Oink，用来把文件发到其它设备" to "他のアプリの共有/開くメニューに Oink を表示します",
        "开启后，电脑可以操控本机，Oink 也能在后台读取剪贴板。需要用到无线调试。" to "有効にすると PC からの操作とバックグラウンドでのクリップボード読み取りが可能になります（ワイヤレスデバッグ使用）。",
        "未开启" to "オフ",
        "设置中" to "設定中",
        "正在启动" to "起動中",
        "已开启" to "有効",
        "启动失败" to "起動失敗",
        "关于" to "情報",
        "版本" to "バージョン",
        "恢复默认" to "リセット",
        "已退出设备组" to "グループから退出しました",
        "连接服务没能启动" to "接続サービスの起動に失敗",
        "连接没能启动" to "接続開始に失敗",

        "开启开发者选项" to "開発者向けオプションを有効化",
        "在「设置 › 关于手机」中，连续点按「版本号」7 次。已开启可以跳过。" to "「設定 › 端末情報」で「ビルド番号」を 7 回タップしてください。",
        "打开关于手机" to "端末情報を開く",
        "打开无线调试" to "ワイヤレスデバッグを有効化",
        "在「开发者选项」中打开「无线调试」。手机需要连接 Wi‑Fi。" to "「開発者向けオプション」で「ワイヤレスデバッグ」をオンにします（Wi-Fi 必須）。",
        "打开开发者选项" to "開発者向けオプションを開く",
        "输入配对码" to "ペアリングコードを入力",
        "留在「设置」里：在「无线调试」中点按「使用配对码配对设备」，然后在 Oink 的通知里点「输入配对码」，填入系统显示的六位配对码，部分手机可能需要长按通知显示按钮。切回 Oink 会让系统关掉配对窗口；分屏同时打开「设置」和 Oink 时，也可以填在下面。" to "設定画面のまま「ペア設定コードによるデバイスのペア設定」をタップし、Oink の通知から 6 桁のコードを入力してください。分割画面時は下に入力できます。",
        "分屏时在这里输入" to "分割画面での入力欄",
        "正在查找配对服务…" to "ペアリングサービスを検索中…",
        "配对并开启" to "ペアリングして開始",
        "取消设置" to "設定を中止",
        "正在开启特权模式" to "特権モードを開始中",
        "通常只需要几秒钟。" to "通常数秒で完了します。",
        "特权模式已开启" to "特権モードが有効です",
        "电脑可以用键盘鼠标操控本机，剪贴板可以自动读取并广播。" to "PC からこの端末を操作でき、クリップボードの自動共有も有効です。",
        "重启后需先在开发者选项中打开无线调试，再回到 Oink 启动。" to "再起動後はワイヤレスデバッグをオンにして Oink を開いてください。",
        "Android 13 及以上，手机重启后会自动恢复特权模式。" to "Android 13 以降では再起動後に自動で復旧します。",
        "开机后自动恢复" to "起動後に自動復旧",
        "重置会清除本组所有手机的配对凭据，之后需要重新配对。系统「无线调试 › 已配对的设备」中的旧 Oink 记录需要手动删除。" to "すべてのペアリング資格情報を消去します。「ペア設定済みのデバイス」の古い履歴は手動で削除してください。",
        "关闭特权模式" to "特権モードを無効化",
        "重置配对凭据" to "資格情報をリセット",
        "特权模式启动失败" to "特権モードの起動に失敗",
        "无法连接无线调试。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "ワイヤレスデバッグに接続できません。Wi-Fi とワイヤレスデバッグがオンか確認してください。",
        "重新配对" to "再ペアリング",
        "特权模式未开启" to "特権モードはオフです",
        "开启后，电脑可以用键盘鼠标操控本机；Oink 可以在后台读取剪贴板并自动广播，控制中心的「粘贴」不用打开 Oink 就能发送文字。" to "有効にすると PC からのキーボード・マウス操作やバックグラウンドでのクリップボード送信が可能になります。",
        "开始设置" to "設定を開始",
        "会改动的系统设置" to "変更されるシステム設定",
        "也可以把手机用 USB 连到电脑，在电脑上的 Oink 中开启。" to "USB で PC に接続し、PC 版 Oink から有効にすることもできます。",
        "开发者选项" to "開発者向けオプション",
        "需要先开启" to "先に有効化が必要",
        "无线调试" to "ワイヤレスデバッグ",
        "用于启动特权服务，需要 Wi‑Fi" to "特権サービスの起動に使用（Wi-Fi 必要）",
        "ADB 授权超时" to "ADB 認証のタイムアウト",
        "会被关闭，以便重启后自动恢复" to "自動復旧のため無効化されます",
        "系统没有允许 Oink 使用无线调试。" to "システムによりワイヤレスデバッグが拒否されました。",
        "无线调试的配对已失效，请重新配对。" to "ペアリングが失効しました。再ペアリングしてください。",
        "特权服务没能启动，请重试。" to "特権サービスを起動できませんでした。再試行してください。",
        "无线调试不可用。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "ワイヤレスデバッグを利用できません。Wi-Fi 接続を確認してください。",
        "无法连接无线调试。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "ワイヤレスデバッグに接続できません。Wi-Fi 接続を確認してください。",
        "Oink 使用了以上开源项目，感谢它们的作者。" to "Oink は上記のオープンソースソフトウェアを使用しています。",
        "无法打开系统设置" to "設定を開けません",
        "无法重置配对凭据" to "資格情報をリセットできません",
        "分享已中断，请重新分享" to "共有が中断されました。やり直してください",
        "特权恢复" to "特権復旧",
        "Oink 正在恢复特权连接" to "特権接続を復旧中",
        "等待可信无线网络和无线调试服务" to "信頼済み Wi-Fi とデバッグサービスを待機中",
        "配对码是六位数字，请重新输入。" to "6 桁の数字を入力してください。",
        "特权模式配对" to "特権モードのペアリング",
        "六位配对码" to "6 桁コード",
        "刚才的配对码没有成功。请输入「使用配对码配对设备」显示的新配对码，不用离开「设置」。" to "失敗しました。設定画面のまま新しい 6 桁コードを入力してください。",
        "请输入「使用配对码配对设备」显示的六位配对码，不用离开「设置」。" to "設定画面のまま「ペア設定コードによるデバイスのペア設定」の 6 桁コードを入力してください。",
        "重新输入配对码" to "コードを再入力",
        "已找到配对服务" to "ペアリングサービスを発見",
        "正在配对…" to "ペアリング中…",
        "正在开启特权模式。" to "特権モードを開始しています。",
        "系统的配对码已失效：离开「设置」时系统会关闭配对码对话框。请在「无线调试」中重新点按「使用配对码配对设备」，不要离开「设置」，在 Oink 的通知里输入新的配对码。" to "設定画面を離れたためコードが失効しました。再表示して通知から入力してください。",
        "配对码不正确或已过期。请在「无线调试」中重新点按「使用配对码配对设备」，在 Oink 的通知里输入新的配对码。" to "コードが無効または期限切れです。新しいコードを発行して通知から入力してください。",
        "无法完成配对，请重试。" to "ペアリングを完了できませんでした。",

        "粉色" to "ピンク",
        "橙色" to "オレンジ",
        "紫色" to "パープル",
        "蓝色" to "ブルー",
        "青色" to "ティール",
        "请先选择要控制的电脑" to "操作する PC を先に選択してください",
        "调节" to "調整",
        "全屏" to "全画面",
        "收起键盘" to "キーボードを閉じる",
        "弹出键盘" to "キーボードを開く",
        "恢复" to "元に戻す",
        "切换键盘：换到模拟键盘（发送原始键码）" to "仮想キーボードに切り替え",
        "切换键盘：换到系统键盘（手机输入法）" to "システムキーボードに切り替え",
        "选择设备" to "デバイスを選択",
        "指针未锁定" to "ポインタ未ロック",
        "指针已锁定" to "ポインタロック中",
        "游戏模式已开启" to "ゲームモード有効",
        "游戏模式已开启（自动）" to "ゲームモード有効（自動）",
        "更多" to "詳細",
        "键盘布局" to "キーボード配列",
        "单指划动" to "1 本指スワイプ",
        "键盘自动弹出" to "自動キーボード表示",
        "指针速度" to "ポインター速度",
        "慢" to "遅い",
        "快" to "速い",
        "可控制的在线设备" to "操作可能なオンラインデバイス",
        "解锁指针" to "ロック解除",
        "锁定指针" to "ポインタ固定",
        "触摸板" to "タッチパッド",
        "触摸移动" to "タッチで移動",
        "轻触单击" to "タップでクリック",
        "手指已锁定" to "ドラッグ固定中",
        "单击" to "クリック",
        "双击" to "ダブルクリック",
        "右键" to "右クリック",
        "释放：抬起锁定的手指" to "解放：固定を解除",
        "释放" to "解放",
        "拖动键盘" to "キーボードをドラッグ",
        "已按下" to "押下中",
        "未按下" to "離した",
        "展开全屏键盘" to "全画面キーボードに展開",
        "切换扬声器模式" to "スピーカーモード切替",
        "快退 / 上一首" to "戻す / 前へ",
        "播放 / 暂停" to "再生 / 一時停止",
        "快进 / 下一首" to "進める / 次へ",
        "音量-" to "音量-",
        "音量+" to "音量+",
        "向前删除" to "前方削除",
        "还原：回到浮动键盘" to "フローティングに戻る",
        "退格" to "Backspace",
        "回车" to "Enter",
        "空格" to "スペース",
        "左" to "左",
        "上" to "上",
        "下" to "下",
        "右" to "右",
        "按住" to "保持",
        "锁定" to "ロック",
        "解锁" to "ロック解除",
        "字母" to "文字",
        "符号" to "記号",
        "移动指针" to "カーソル移動",
        "滚动" to "スクロール",
        "美式英语（US）" to "英語 (US)",
        "英式英语（UK）" to "英語 (UK)",
        "德语（QWERTZ）" to "ドイツ語 (QWERTZ)",
        "法语（AZERTY）" to "フランス語 (AZERTY)",
        "西班牙语" to "スペイン語",
        "日语（JIS）" to "日本語 (JIS)",
        "韩语" to "韓国語",
        "输入到焦点电脑" to "フォーカス PC に入力",

        // Control Overlay
        "按键映射" to "ボタンマッピング",
        "编辑按键" to "ボタンを編集",
        "添加按键" to "ボタン追加",
        "配置方案" to "プロファイル",
        "完成" to "完了",
        "绑定按键" to "割り当て",
        "形状" to "形状",
        "圆形" to "丸型",
        "胶囊" to "ピル型",
        "大小" to "サイズ",
        "触发方式" to "モード",
        "按住触发" to "長押し",
        "切换开关" to "トグル",
        "文字标签" to "ラベル",
        "重命名" to "名前変更",
        "新建方案" to "新規プロファイル",
        "透明度" to "不透明度",
        "选择要绑定的键位或鼠标按键" to "キーボードまたはマウスボタンを選択",
        "鼠标按键" to "マウスボタン",
        "常用按键" to "よく使うキー",
        "字母键" to "文字キー",
        "数字键" to "数字キー",
        "方向键" to "矢印キー",
        "功能键" to "ファンクションキー",
        "左键" to "左クリック",
        "中键" to "中クリック",
        "方案名称" to "プロファイル名",
        "关闭按键映射" to "オーバーレイを閉じる",

        // Pairing errors & Rust bridge
        "请输入有效的 IPv4 地址" to "有効な IPv4 アドレスを入力してください",
        "不能与当前设备配对" to "自身とはペアリングできません",
        "目标主机不可达" to "ホストに到達できません",
        "连接被拒绝" to "接続が拒否されました",
        "连接超时" to "接続タイムアウト",
        "该地址上没有 Oink 设备" to "このアドレスに Oink が見つかりません",
        "设备身份已变化，请重新搜索" to "デバイス識別子が変更されました",
        "对方的协议版本不兼容" to "プロトコルバージョン非互換",
        "对方不支持所需功能" to "必要な機能に対応していません",
        "设备正忙，请稍后重试" to "デバイスがビジー状態です",
        "操作过于频繁，请稍后重试" to "試行回数が多すぎます",
        "对方拒绝了配对" to "ペアリングが拒否されました",
        "对方取消了配对" to "ペアリングが中止されました",
        "已取消配对" to "ペアリングをキャンセルしました",
        "对方关闭了配对" to "ペアリングが閉じられました",
        "连接已断开" to "接続が切れました",
        "请输入六位配对码" to "6 桁コードを入力してください",
        "配对码不正确，请重新输入" to "コードが誤っています",
        "尝试次数已用完" to "試行回数上限に達しました",
        "此配对已结束" to "ペアリングは終了しました",
        "设备已经连接" to "すでに接続済みです",
        "已由另一配对会话接替" to "別のセッションに引き継がれました",
        "设备组已变化" to "グループが変更されました",
        "设备数量已达上限" to "デバイス上限に達しました",
        "设备组数据超过限制" to "グループデータ上限超過",
        "无法验证设备组成员" to "メンバーを確認できません",
        "设备组合并冲突" to "グループの競合",
        "无法保存配对结果" to "ペアリング結果を保存できません",
        "无法确认提交结果" to "コミット結果を確認できません",
        "无法获取合并证明" to "証明を取得できません",
        "设备成员资格已撤销" to "メンバー資格が取り消されました",
        "设备组已变化，无法撤销" to "グループ変更のため取り消せません",
        "不允许此操作" to "許可されていない操作です",
        "搜索结果的连接已断开，请重新搜索" to "検索結果の接続が切れました",
        "配对状态已变化，请重试" to "状態が変化しました。再試行してください",
        "配对协议异常" to "ペアリングプロトコルエラー",
        "设备状态已变化，请重新搜索" to "デバイス状態が変化しました",
        "配对已经提交" to "ペアリングは確定済みです",
        "无法验证设备身份" to "デバイスを認証できません",
        "没有共同支持的加密方式" to "共通の暗号方式がありません",
        "不支持该屏幕排列" to "非対応の画面配置です",
        "配对服务不可用" to "ペアリングサービスが利用不可です",
        "网络操作超时" to "ネットワークタイムアウト",
        "对方还有没处理的配对请求，请稍后重试" to "相手に未処理のリクエストがあります",
        "配对状态未知" to "ペアリング状態不明",
        "本机没有连接网络" to "ネットワーク未接続",
        "核心事件处理失败" to "コアイベント処理失敗",
        "设置已生效" to "設定を適用しました",
        "设置未变化" to "設定変更なし",
        "设置等待确认" to "確認待ち",
        "地址查询已失效，请重新查找" to "アドレス検索が失効しました",
        "配对已完成" to "ペアリング完了",
        "配对提交结果待确认，请检查设备列表" to "未確認：デバイス一覧をご確認ください",
        "未知设置" to "不明な設定",
        "无法识别图片格式" to "画像形式を認識できません",
        "图片太大，无法复制" to "画像が大きすぎます",
        "正在连接对方…" to "相手に接続中…",
        "正在验证并合并设备组…" to "検証・統合中…",
        "等待对方接受并输入验证码" to "相手の入力を待機中",
        "请输入对方显示的六位码" to "相手画面の 6 桁コードを入力",
        "对方的状态已变化，请重试" to "状態が変化しました。再試行してください",
        "设置值无效" to "無効な値です",
        "对方无法保存设置" to "相手側で設定を保存できませんでした",
        "对方无法应用设置" to "相手側で設定を適用できませんでした",
        "不确定是否已生效，请检查对方状态" to "状態をご確認ください",
        "该设备不是音频出口" to "出力先デバイスではありません",
        "音频线路已变化，请重试" to "音声ルートが変化しました",
        "音频输出设备已变化，请重试" to "出力デバイスが変化しました",
        "对方没有可用的音频输出" to "利用可能な出力がありません",
        "对方不支持调节音量" to "音量調整に非対応です",
        "对方没有授予所需权限" to "相手側に必要な権限がありません",
        "已被新的设置取代" to "新しい設定で上書きされました",
        "对方的状态有冲突，请重试" to "状態が競合しています",
        "未知原因" to "不明な原因",
        "扬声器转发音量" to "スピーカー転送音量",
        "麦克风转发音量" to "マイク転送音量",
        "恢复此设备" to "このデバイスを再開",
        "暂停此设备" to "このデバイスを一時停止",
        "共享屏幕" to "画面を共有",
        "屏幕仅本机" to "画面ローカルのみ",
        "共享鼠标" to "マウスを共有",
        "鼠标仅本机" to "マウスローカルのみ",
        "禁用鼠标" to "マウスを無効",
        "共享键盘" to "キーボードを共有",
        "键盘仅本机" to "キーボードローカルのみ",
        "禁用键盘" to "キーボードを無効",
        "启用键盘" to "キーボードを有効",
        "剪贴板仅本机" to "クリップボード共有オフ",
        "启用音频转发" to "音声転送をオン",
        "关闭音频转发" to "音声転送をオフ",
        "取消音频出口" to "出力先を解除",
        "设为音频出口" to "出力先に設定",
        "麦克风仅本机" to "マイクローカルのみ",
        "接收麦克风" to "マイクを受信",
        "游戏模式自动" to "ゲームモード自動",
        "开启游戏模式" to "ゲームモードオン",
        "关闭游戏模式" to "ゲームモードオフ",
        "解除指针锁定" to "ロック解除",
        "锁定指针到此设备" to "ここにロック",
        "屏幕" to "画面",
        "输出音量" to "出力音量",
        "设置被拒绝" to "設定が拒否されました",
        "设置失败" to "設定に失敗しました",
        "对端缺少能力" to "相手側に必要な機能がありません",
        "对端协议不兼容" to "プロトコルが非互換です",
        "无法保存到相册" to "写真に保存できませんでした",
        "停止设备广播失败" to "デバイス通知の停止に失敗",
        "局域网发现失败" to "LAN 検出に失敗しました",
        "停止局域网发现失败" to "LAN 検出の停止に失敗",
        "解析附近设备失败" to "近くのデバイスの解決に失敗",
        "订阅附近设备失败" to "近くのデバイスの監視に失敗",
        "无法分享" to "共有できません",
        "对方设备" to "相手デバイス",
        "对方不在线" to "相手はオフラインです",
        "所有在线设备" to "オンラインのすべてのデバイス",
        "此内容请选一台电脑" to "これには PC を選択してください",
        "没有其它设备在线" to "他にオンラインのデバイスがありません",
        "下一项" to "次の項目",
        "上一项" to "前の項目",
        "展开" to "展開",
        "文字" to "テキスト",
        "音频转发" to "音声転送",
        "分享内容为空或不受支持" to "共有内容が空または非対応です",
        "只能读取获授权的内容 URI" to "許可されたコンテンツ URI のみ読み取れます",
        "无法创建暂存目录" to "一時ディレクトリを作成できません",
        "无法保存照片" to "写真を保存できません",
        "无法读取文件夹" to "フォルダを読み取れません",
        "文件夹内容还在加载，请稍后重新选择" to "フォルダを読み込み中です。しばらくしてから選択し直してください",
        "还没有下载完成" to "ダウンロードがまだ完了していません",
        "无法打开" to "開けません",
        "此设备未提供状态" to "このデバイスから状態が報告されていません",
    )

    internal val KO_MAP: Map<String, String> = mapOf(
        "设备" to "기기",
        "传输" to "전송",
        "控制" to "제어",
        "设置" to "설정",
        "共享剪贴板" to "공유 클립보드",
        "添加设备" to "기기 추가",
        "本机信息" to "기기 정보",
        "打开设置" to "설정 열기",
        "关闭设置" to "설정 닫기",
        "返回" to "뒤로",
        "关闭" to "닫기",
        "取消" to "취소",
        "确认" to "확인",
        "保存" to "저장",
        "知道了" to "확인",
        "重试" to "다시 시도",
        "查找" to "검색",
        "配对" to "페어링",
        "取消配对" to "페어링 취소",
        "退出" to "종료",
        "退出 Oink" to "Oink 종료",
        "退出设备组" to "그룹 나가기",

        "电脑连接" to "PC 연결",
        "打开 Oink" to "Oink 열기",
        "复制内容" to "공유 복사",
        "停止接收音频" to "오디오 수신 중지",
        "停止转发音频" to "오디오 전달 중지",
        "停止共享麦克风" to "마이크 공유 중지",
        "Oink · 点按从共享剪贴板复制" to "Oink · 탭하여 공유 클립보드 복사",
        "Oink · 运行中" to "Oink · 실행 중",
        "已复制" to "복사됨",
        "转发音频" to "오디오 전달",
        "音频出口" to "오디오 출력",
        "麦克风共享" to "마이크 공유",
        "复制" to "복사",
        "粘贴" to "붙여넣기",
        "接收音频" to "오디오 수신",
        "共享麦克风" to "마이크 공유",
        "Oink 未运行" to "Oink 미실행",
        "未配对" to "미페어링",
        "需特权模式" to "권한 모드 필요",
        "转发中" to "전달 중",
        "获取中" to "가져오는 중",
        "没有内容" to "내용 없음",
        "复制失败" to "복사 실패",
        "发送中" to "보내는 중",
        "已发送" to "전송됨",
        "发送失败" to "전송 실패",
        "收到的文件" to "받은 파일",
        "发送进度" to "전송 진행률",

        "正在连接…" to "연결 중…",
        "已连接局域网" to "LAN 연결됨",
        "网络出错，正在重新连接…" to "네트워크 오류, 다시 연결 중…",
        "连接离线" to "연결 끊김",
        "网络绑定失败，使用系统默认网络" to "네트워크 고정 실패, 기본 네트워크 사용",
        "局域网发现启动失败" to "LAN 탐색 시작 실패",
        "停止广播失败" to "브로드캐스트 중지 실패",
        "设备广播失败" to "기기 알림 실패",
        "无法读取传输设置" to "전송 설정을 읽을 수 없음",
        "无法读取设备组" to "기기 그룹을 읽을 수 없음",
        "无法离开设备组" to "기기 그룹을 나갈 수 없음",
        "本机 · 离线" to "이 기기 · 오프라인",
        "本机" to "이 기기",
        "离线" to "오프라인",
        "在线" to "온라인",
        "正在连接" to "연결 중",
        "选择期间 Oink 被系统关闭，请重新选择" to "선택 중 앱이 종료되었습니다. 다시 선택하세요",
        "Rust 核心尚未安装，请先生成移动绑定" to "Rust 코어가 로드되지 않았습니다",
        "无法读取音频设置" to "오디오 설정을 읽을 수 없음",
        "传输失败，请重试" to "전송 실패, 다시 시도하세요",
        "下载失败，请重试" to "다운로드 실패, 다시 시도하세요",
        "发现结果更新失败" to "탐색 결과 업데이트 실패",
        "发现结果撤回失败" to "탐색 결과 철회 실패",
        "剪贴板历史同步失败" to "클립보드 기록 동기화 실패",
        "连接尚未就绪，请稍候" to "연결이 준비되지 않았습니다. 잠시만 기다리세요",
        "操作失败" to "작업 실패",
        "音频出口音量暂不可调" to "오디오 출력 음량을 지금 조절할 수 없습니다",
        "无法修改设备名" to "기기 이름을 변경할 수 없습니다",
        "无法修改自动接收设置" to "자동 수신 설정을 변경할 수 없습니다",
        "转发音频仅在特权模式可用" to "오디오 전달은 권한 모드에서만 사용할 수 있습니다",
        "请在 Oink 中允许使用麦克风" to "Oink에서 마이크 권한을 허용하세요",
        "没有麦克风权限，无法开启麦克风" to "마이크 권한이 없습니다",
        "无法统计缓存" to "캐시 크기를 계산할 수 없습니다",
        "无法读取传输记录" to "전송 기록을 읽을 수 없습니다",
        "无法删除消息" to "메시지를 삭제할 수 없습니다",
        "无法清理传输缓存" to "캐시를 지울 수 없습니다",
        "无法清理传输记录" to "전송 기록을 지울 수 없습니다",
        "下载任务暂不可用" to "지금 다운로드할 수 없습니다",
        "请稍后重试" to "잠시 후 다시 시도하세요",
        "无法打开文件" to "파일을 열 수 없습니다",
        "此设备的麦克风音量暂不可调" to "이 기기의 마이크 음량을 조절할 수 없습니다",
        "此设备的扬声器音量暂不可调" to "이 기기의 스피커 음량을 조절할 수 없습니다",
        "没有可用的应用" to "사용 가능한 앱이 없습니다",
        "此设备暂时无法暂停" to "지금 이 기기를 일시 중지할 수 없습니다",
        "无法修改 IP" to "IP를 수정할 수 없습니다",
        "未知错误" to "알 수 없는 오류",
        "未知" to "알 수 없음",
        "无法创建相册项目" to "갤러리 항목을 만들 수 없습니다",
        "已保存到相册" to "사진에 저장됨",
        "没有可复制的内容" to "복사할 내용이 없습니다",
        "剪贴板是空的" to "클립보드가 비어 있습니다",
        "剪贴板里的内容不能发送" to "클립보드 내용을 보낼 수 없습니다",
        "分享收到的内容" to "받은 내용 공유",
        "Oink 没有在运行" to "Oink가 실행 중이 아닙니다",
        "与电脑的连接中断，请重试" to "PC와 연결이 끊어졌습니다. 다시 시도하세요",
        "键鼠共享已暂停，输入没有发出" to "입력 공유가 일시 중지되었습니다",
        "这台设备不支持这项操作" to "이 기기에서는 지원하지 않는 작업입니다",
        "焦点电脑已暂停共享，文字没有输入" to "대상 PC가 공유를 일시 중지했습니다",
        "焦点电脑没有加入键鼠共享，文字没有输入" to "대상 PC가 입력 공유에 참여하지 않았습니다",
        "这台设备不能输入文字" to "이 기기에는 텍스트를 입력할 수 없습니다",
        "转发音频失败：系统音频正被其它功能占用（如投屏）" to "오디오 전달 실패: 시스템 오디오가 다른 기능에서 사용 중입니다",
        "请解锁手机后再开始转发" to "잠금을 해제한 후 오디오 전달을 시작하세요",
        "转发音频已中断，请重试" to "오디오 전달이 중단되었습니다. 다시 시도하세요",
        "调节本机音量不影响转发" to "휴대전화 음량은 전달 오디오에 영향을 주지 않습니다",
        "转发中本机静音" to "전달 중 기기 스피커 음소거됨",
        "无法复制内容" to "내용을 복사할 수 없습니다",
        "无法写入手机剪贴板" to "클립보드에 쓸 수 없습니다",
        "接收内容已过期，请重新获取" to "만료된 내용입니다. 다시 가져오세요",
        "已接收并复制到手机剪贴板" to "클립보드에 복사됨",
        "正在准备分享…" to "공유 준비 중…",
        "正在读取内容，请完成后重新选择" to "이전 콘텐츠를 읽는 중입니다. 잠시 후 다시 시도하세요",
        "正在发送，请完成后重新分享" to "전송 중입니다. 완료 후 다시 공유하세요",
        "已取消发送" to "전송 취소됨",
        "已取消" to "취소됨",

        "在线电脑没有可获取的剪贴板内容。请先在电脑上复制内容，再重试。" to "온라인 PC에서 가져올 클립보드 내용이 없습니다. PC에서 복사한 후 다시 시도하세요.",
        "连接中断，请重试" to "연결 끊김, 다시 시도하세요",
        "没有在线的电脑" to "온라인 PC 없음",
        "来源设备已离线，上线后再试" to "원본 기기가 오프라인입니다",
        "来源设备已不再保留此内容" to "원본 기기에 이 내용이 더 이상 없습니다",
        "来源设备上已没有此内容" to "원본 기기에서 찾을 수 없습니다",
        "此内容已被来源设备上的新内容替换" to "원본 기기의 새 내용으로 대체됨",
        "来源设备无法提供此内容" to "원본 기기에서 제공할 수 없습니다",
        "共享内容已过期" to "공유 내용 만료됨",
        "这条消息已不存在" to "메시지가 존재하지 않습니다",
        "本机发送的内容没有可下载的来源" to "이 기기가 보낸 항목입니다",
        "正在下载中" to "다운로드 중",
        "手机存储空间不足" to "저장 공간이 부족합니다",
        "无法读取要发送的内容" to "보낼 내용을 읽을 수 없습니다",
        "存储空间不足或无法写入" to "저장 공간 부족 또는 쓰기 실패",
        "对方版本不兼容" to "상대방 버전이 호환되지 않습니다",
        "对方已暂停共享" to "상대방이 공유를 일시 중지했습니다",
        "对方关了同步发送" to "상대방이 동기화 전송을 껐습니다",
        "文件夹内容已失效" to "폴더 내용이 만료되었습니다",
        "无法获取文件夹内容" to "폴더를 불러올 수 없습니다",
        "没有在线的设备" to "온라인 기기 없음",
        "对方没有响应" to "응답 없음",
        "对方拒绝接收" to "상대방이 거절함",
        "对方无法接收" to "상대방이 받을 수 없음",
        "连接中断" to "연결 중단됨",
        "对方已断开" to "연결 끊김",
        "对方已断开，发送失败" to "연결 끊김, 전송 실패",
        "对方已断开，接收失败" to "연결 끊김, 수신 실패",
        "设备不在线" to "기기가 오프라인입니다",
        "对方的 Oink 版本不支持此操作" to "상대방 Oink 버전이 지원하지 않습니다",
        "对方的 Oink 版本不支持此设置" to "상대방 Oink 버전이 이 설정을 지원하지 않습니다",
        "还没有收到对方的状态，请稍候" to "상태를 기다리는 중입니다",
        "对方的状态已过期，请稍候" to "기기 상태가 만료되었습니다",
        "对方设备出现故障" to "상대방 기기 오류",
        "该设备不在设备组中" to "그룹에 없는 기기입니다",
        "对方正忙，请稍后再试" to "상대방이 사용 중입니다",
        "此操作暂不可用" to "지금 사용할 수 없습니다",
        "请先在电脑上授予 Oink 所需权限" to "먼저 PC에서 Oink 권한을 허용하세요",
        "IP 地址格式不正确" to "올바르지 않은 IP 형식",
        "无法保存，请重试" to "저장 실패, 다시 시도하세요",
        "不能屏蔽本机" to "이 기기는 차단할 수 없습니다",
        "无法保存屏蔽列表，请重试" to "차단 목록을 저장할 수 없습니다",
        "屏蔽列表操作失败" to "차단 목록 작업 실패",
        "正在获取…" to "불러오는 중…",
        "获取失败" to "불러오기 실패",
        "版本过低，请升级" to "버전이 너무 낮습니다. 업데이트하세요",
        "上次在线" to "최근 온라인",
        "很久" to "오래",

        "还没有其它设备" to "다른 기기가 없습니다",
        "添加一台电脑或手机，就能共享键鼠、剪贴板、文件和音频。" to "PC나 휴대전화를 추가하면 키보드·마우스, 클립보드, 파일, 오디오를 공유할 수 있습니다.",
        "Android，特权模式已开启" to "Android, 권한 모드 켜짐",
        "鼠标" to "마우스",
        "键盘" to "키보드",
        "音频" to "오디오",
        "麦克风" to "마이크",
        "扬声器" to "스피커",
        "共享" to "공유",
        "本地" to "로컬",
        "禁用" to "비활성화",
        "已禁用" to "비활성화됨",
        "指针锁定" to "포인터 잠금",
        "开启" to "켬",
        "关闭" to "끔",
        "游戏模式" to "게임 모드",
        "自动" to "자동",
        "转发到出口" to "출력 기기로 전달",
        "作为出口" to "출력 기기로 설정",
        "转发" to "전달",
        "接收" to "수신",
        "发送" to "송신",
        "开" to "켬",
        "关" to "끔",
        "游戏模式：自动" to "게임 모드: 자동",
        "已暂停" to "일시 정지됨",
        "已继续" to "재개됨",
        "继续" to "재개",
        "暂停" to "일시 정지",
        "踢出设备" to "기기 제거",
        "无法远程控制此设备" to "이 기기는 원격 제어할 수 없습니다",
        "编辑 IP" to "IP 편집",
        "踢出后，需要重新配对才能再次和它共享" to "제거 후 다시 공유하려면 페어링이 필요합니다",
        "踢出" to "제거",
        "IP 已更新" to "IP 업데이트됨",
        "IP 地址" to "IP 주소",
        "正在保存…" to "저장 중…",
        "请输入 IP 地址" to "IP 주소를 입력하세요",
        "请输入有效的 IP 地址" to "유효한 IP 주소를 입력하세요",
        "端口需为 1–65535，或留空" to "포트는 1–65535 또는 비워 두기",
        "本地，音量固定为零" to "로컬, 음량 0 고정",

        "配对请求" to "페어링 요청",
        "附近设备" to "주변 기기",
        "刷新附近设备" to "주변 기기 새로고침",
        "协议版本不兼容" to "프로토콜 버전 호환 안 됨",
        "正在搜索附近的设备" to "주변 기기 검색 중",
        "手动添加" to "직접 추가",
        "请确认对方已打开 Oink，并和本机连接到同一个网络。" to "상대 기기에서 Oink가 실행 중이고 같은 네트워크에 연결되어 있는지 확인하세요.",
        "本机 IP" to "로컬 IP",
        "本机端口" to "로컬 포트",
        "本机 ID" to "기기 ID",
        "点按任一项即可复制" to "항목을 탭하여 복사",
        "请输入有效的 IPv4 地址，例如 192.168.1.20" to "유효한 IPv4 주소를 입력하세요 (예: 192.168.1.20)",
        "端口应在 1–65535 之间" to "포트는 1–65535 사이여야 합니다",
        "输入对方的 IP 地址。对方的地址显示在它「添加设备」页的底部。" to "상대 기기의 '기기 추가' 화면에 표시된 IP 주소를 입력하세요.",
        "端口" to "포트",
        "正在查找…" to "검색 중…",
        "配对失败" to "페어링 실패",
        "配对成功" to "페어링 성공",
        "等待对方输入" to "상대방 입력 대기 중",
        "正在验证…" to "확인 중…",
        "配对后，对方可以和本机共享剪贴板、文件和音频；开启特权模式后还能操控本机。请确认你信任此设备。" to "페어링하면 클립보드, 파일, 오디오를 공유하고 권한 모드에서 이 기기를 제어할 수 있습니다. 신뢰하는 기기인지 확인하세요.",

        "更早的记录仅保存在本机" to "이전 기록은 이 기기에만 저장됨",
        "加载失败" to "불러오기 실패",
        "加载更早的记录" to "이전 기록 불러오기",
        "正在加载更早的记录…" to "이전 기록 불러오는 중…",
        "已显示最近 100 条共享记录" to "최근 공유 기록 100개 표시 중",
        "更早的本机记录" to "이전 로컬 기록",
        "隐藏" to "숨기기",
        "隐藏内容" to "내용 숨기기",
        "收起" to "접기",
        "收起为一叠" to "스택으로 접기",
        "未选择" to "선택 안 함",
        "添加附件" to "첨부 추가",
        "当前活跃" to "활성 기기",
        "电脑" to "컴퓨터",
        "移动设备" to "모바일 기기",
        "删除这条消息？" to "이 메시지를 삭제할까요?",
        "删除" to "삭제",
        "删除后无法恢复。" to "삭제하면 되돌릴 수 없습니다.",
        "已下载的文件会一起删除，删除后无法恢复。" to "다운로드한 파일도 함께 삭제되며 되돌릴 수 없습니다.",
        "拍摄" to "카메라",
        "图库" to "갤러리",
        "文件" to "파일",
        "文件夹" to "폴더",
        "取消发送" to "전송 취소",
        "取消接收" to "수신 취소",
        "复制文本" to "텍스트 복사",
        "选择" to "선택",
        "正在发送…" to "보내는 중…",
        "经" to "경유",
        "已隐藏的内容" to "숨겨진 내용",
        "查看更多" to "더 보기",
        "显示" to "표시",
        "选择文字" to "텍스트 선택",
        "图片已失效" to "이미지 만료됨",
        "图片暂时无法加载" to "이미지를 불러올 수 없음",
        "打开图片" to "이미지 열기",
        "接收失败" to "수신 실패",
        "暂时无法加载" to "지금 불러올 수 없음",
        "加载失败，点按重试" to "실패, 탭하여 재시도",
        "已失效" to "만료됨",
        "下载失败" to "다운로드 실패",
        "用其它应用打开" to "다른 앱으로 열기",
        "分享" to "공유",
        "无法显示这张图片" to "이미지를 표시할 수 없음",
        "下载" to "다운로드",
        "文件已不在这台手机上" to "파일이 이 기기에 없습니다",
        "内容已失效" to "내용이 만료됨",
        "来源设备离线，暂时无法获取" to "원본 기기가 오프라인입니다",
        "保存到相册" to "사진에 저장",
        "无法显示这个 PDF" to "이 PDF를 표시할 수 없음",
        "无法播放这个视频" to "이 동영상을 재생할 수 없음",
        "播放" to "재생",
        "取消静音" to "음소거 해제",
        "静音" to "음소거",
        "播放进度" to "재생 진행률",
        "发送到" to "보낼 대상",
        "正在查找设备…" to "기기 찾는 중…",
        "请在其它设备上打开 Oink" to "다른 기기에서 Oink를 여세요",
        "Oink 正在发送" to "Oink 전송 중",
        "正在发送给" to "보낼 대상: ",
        "已完成" to "완료됨",
        "对方不在线，请在对方设备打开 Oink\n并确保网络连接畅通" to "상대방이 오프라인입니다. 상대 기기에서 Oink를 열고 네트워크 연결을 확인하세요",
        "输入文字，随手传输…" to "보낼 텍스트 입력…",

        "1 分钟" to "1분",
        "5 分钟" to "5분",
        "10 分钟" to "10분",
        "20 分钟" to "20분",
        "30 分钟" to "30분",
        "60 分钟" to "60분",
        "无限制" to "제한 없음",
        "单击扬声器按钮行为" to "스피커 버튼 탭 동작",
        "设为出口" to "출력 기기로 설정",
        "深浅色" to "테마",
        "跟随系统" to "시스템 설정 따르기",
        "浅色" to "라이트",
        "深色" to "다크",
        "语言" to "언어",
        "默认页面" to "시작 페이지",
        "下次打开 Oink 时先显示这个页面。" to "Oink를 실행할 때 처음 표시할 화면입니다.",
        "点按通知" to "알림 탭 동작",
        "从共享剪贴板复制" to "공유 클립보드 복사",
        "打开 App" to "앱 열기",
        "从共享剪贴板复制：把其它设备当前复制的文字或图片复制到本机；没有可复制的内容时打开 Oink。" to "다른 기기에서 복사한 텍스트·이미지를 복사하며, 비어 있으면 앱을 엽니다.",
        "打开页面" to "열 페이지",
        "点按通知时打开 Oink 的这个页面。" to "알림을 탭할 때 열 Oink 화면입니다.",
        "麦克风自动关闭" to "마이크 자동 끄기",
        "本机作为麦克风时，如果这段时间里一直没有设备在用，就自动关闭。刚开启时从开启那一刻开始计时。" to "마이크가 이 시간 동안 사용되지 않으면 자동으로 끕니다.",
        "扬声器自动关闭" to "스피커 자동 끄기",
        "本机作为扬声器时，如果这段时间里一直没有收到声音，就自动关闭。刚开启时从开启那一刻开始计时。" to "이 시간 동안 수신된 소리가 없으면 자동으로 끕니다.",
        "图片" to "이미지",
        "JPG、PNG、HEIC、GIF 等" to "JPG, PNG, HEIC, GIF 등",
        "视频" to "동영상",
        "MP4、MOV、MKV 等" to "MP4, MOV, MKV 등",
        "文档" to "문서",
        "MP3、AAC、FLAC 等" to "MP3, AAC, FLAC 등",
        "PDF、Office、文本等" to "PDF, Office, 텍스트 등",
        "压缩包" to "압축 파일",
        "ZIP、RAR、7Z 等" to "ZIP, RAR, 7Z 등",
        "安装包" to "설치 패키지",
        "安装包（APK）" to "설치 패키지 (APK)",
        "其它类型" to "기타",
        "其他类型" to "기타",
        "以上之外的所有文件" to "위 항목 외 모든 파일",
        "特权模式" to "권한 모드",
        "开源许可" to "오픈소스 라이선스",
        "清理缓存？" to "캐시를 비울까요?",
        "清理" to "지우기",
        "清理传输记录？" to "전송 기록을 지울까요?",
        "已清理传输记录" to "전송 기록 삭제됨",
        "将删除本机的所有传输记录，以及已下载的图片和文件。其它设备上的记录不受影响。" to "이 기기의 모든 전송 기록과 다운로드한 파일이 삭제됩니다. 다른 기기에는 영향을 주지 않습니다.",
        "关闭特权模式？" to "권한 모드를 끌까요?",
        "特权模式已关闭" to "권한 모드가 꺼졌습니다",
        "电脑将不能操控本机，自动读取剪贴板会暂停。之后可以直接重新开启，不用再配对。" to "PC가 이 기기를 제어할 수 없으며 클립보드 자동 읽기가 일시 중지됩니다. 다시 페어링하지 않고 언제든 켤 수 있습니다.",
        "重置配对凭据？" to "페어링 자격 증명을 재설정할까요?",
        "重置" to "재설정",
        "已重置，需要重新配对" to "재설정됨, 재페어링 필요",
        "会停止特权服务，并清除本组所有手机的 Oink 配对凭据。每台手机都需要重新配对无线调试。" to "권한 서비스를 중지하고 그룹 내 모든 기기의 무선 디버깅 페어링 정보를 지웁니다.",
        "退出后，需要重新配对才能和组内设备共享。" to "나간 후에는 다시 페어링해야 공유할 수 있습니다.",
        "本机" to "이 기기",
        "设备名" to "기기 이름",
        "外观" to "화면",
        "主题色" to "강조 색상",
        "首页" to "홈",
        "清理缓存会删除已下载的图片和文件，消息记录会保留。" to "캐시를 지우면 다운로드된 이미지와 파일만 삭제되며 메시지 기록은 남습니다.",
        "清理传输记录" to "전송 기록 지우기",
        "清理缓存" to "캐시 지우기",
        "正在清理…" to "지우는 중…",
        "剪贴板" to "클립보드",
        "自动写入剪贴板" to "클립보드 자동 쓰기",
        "其它设备复制的文字会自动写入本机剪贴板" to "다른 기기에서 복사한 텍스트를 자동으로 씁니다",
        "自动读取剪贴板并广播" to "클립보드 자동 읽기 및 공유",
        "本机复制的文字会自动发到其它设备" to "여기서 복사한 텍스트를 다른 기기로 자동 전송합니다",
        "需要先开启特权模式" to "권한 모드를 먼저 켜야 합니다",
        "去开启" to "켜기",
        "开启麦克风或扬声器后，如果一直没有设备在用，到时间会自动关闭。" to "사용하지 않고 일정 시간이 지나면 마이크 또는 스피커를 자동으로 끕니다.",
        "连接就绪后可修改" to "연결 후 수정 가능",
        "回声消除" to "에코 제거",
        "消除麦克风录制的本机播放的音频" to "마이크에 들어가는 기기 출력 소리를 제거합니다",
        "通知与后台" to "알림 및 백그라운드",
        "在控制中心编辑开关，可以添加 Oink 的「复制」「粘贴」「接收音频」「音频转发」「共享麦克风」。" to "빠른 설정에서 Oink의 '복사', '붙여넣기', '오디오 수신', '오디오 전달', '마이크 공유' 타일을 추가할 수 있습니다.",
        "常驻通知" to "상단 고정 알림",
        "在通知栏显示在线设备和音频状态" to "알림창에 온라인 기기와 오디오 상태 표시",
        "已关闭：离开 Oink 后，Oink 将无法在后台工作" to "꺼짐: Oink를 벗어나면 백그라운드에서 작동하지 않습니다",
        "在多任务中隐藏" to "최근 앱 목록에서 숨기기",
        "最近任务列表里不显示 Oink" to "최근 사용 앱 목록에 Oink를 표시하지 않음",
        "打开方式注册" to "연결 프로그램에 등록",
        "在其它应用的「打开方式」里显示 Oink，用来把文件发到其它设备" to "다른 앱의 '다른 앱으로 열기'에 Oink를 표시해 파일을 보냅니다",
        "开启后，电脑可以操控本机，Oink 也能在后台读取剪贴板。需要用到无线调试。" to "켜면 PC가 이 기기를 제어하고 백그라운드에서 클립보드를 읽을 수 있습니다 (무선 디버깅 필요).",
        "未开启" to "꺼짐",
        "设置中" to "설정 중",
        "正在启动" to "시작 중",
        "已开启" to "켜짐",
        "启动失败" to "시작 실패",
        "关于" to "정보",
        "版本" to "버전",
        "恢复默认" to "기본값 복원",
        "已退出设备组" to "기기 그룹에서 나갔습니다",
        "连接服务没能启动" to "연결 서비스를 시작할 수 없음",
        "连接没能启动" to "연결 시작 실패",

        "开启开发者选项" to "개발자 옵션 켜기",
        "在「设置 › 关于手机」中，连续点按「版本号」7 次。已开启可以跳过。" to "'설정 › 휴대전화 정보'에서 '빌드 번호'를 7번 연속 탭하세요.",
        "打开关于手机" to "휴대전화 정보 열기",
        "打开无线调试" to "무선 디버깅 켜기",
        "在「开发者选项」中打开「无线调试」。手机需要连接 Wi‑Fi。" to "'개발자 옵션'에서 '무선 디버깅'을 켜세요. Wi-Fi 연결이 필요합니다.",
        "打开开发者选项" to "개발자 옵션 열기",
        "输入配对码" to "페어링 코드 입력",
        "留在「设置」里：在「无线调试」中点按「使用配对码配对设备」，然后在 Oink 的通知里点「输入配对码」，填入系统显示的六位配对码，部分手机可能需要长按通知显示按钮。切回 Oink 会让系统关掉配对窗口；分屏同时打开「设置」和 Oink 时，也可以填在下面。" to "설정 화면에 머문 채 '페어링 코드로 기기 페어링'을 누르고 Oink 알림에 6자리 코드를 입력하세요. 분할 화면 사용 시 아래에도 입력할 수 있습니다.",
        "分屏时在这里输入" to "분할 화면 입력",
        "正在查找配对服务…" to "페어링 서비스 찾는 중…",
        "配对并开启" to "페어링 및 시작",
        "取消设置" to "설정 취소",
        "正在开启特权模式" to "권한 모드 시작 중",
        "通常只需要几秒钟。" to "보통 몇 초 정도 걸립니다.",
        "特权模式已开启" to "권한 모드가 켜졌습니다",
        "电脑可以用键盘鼠标操控本机，剪贴板可以自动读取并广播。" to "PC에서 키보드·마우스로 제어할 수 있으며 클립보드를 자동으로 공유합니다.",
        "重启后需先在开发者选项中打开无线调试，再回到 Oink 启动。" to "재부팅 후에는 무선 디버깅을 켠 뒤 Oink로 돌아오세요.",
        "Android 13 及以上，手机重启后会自动恢复特权模式。" to "Android 13 이상에서는 재부팅 후 자동으로 복구됩니다.",
        "开机后自动恢复" to "재부팅 후 자동 복구",
        "重置会清除本组所有手机的配对凭据，之后需要重新配对。系统「无线调试 › 已配对的设备」中的旧 Oink 记录需要手动删除。" to "그룹 내 모든 휴대전화의 페어링 정보가 초기화됩니다. 시스템의 '페어링된 기기' 기록은 직접 삭제하세요.",
        "关闭特权模式" to "권한 모드 끄기",
        "重置配对凭据" to "페어링 정보 재설정",
        "特权模式启动失败" to "권한 모드 시작 실패",
        "无法连接无线调试。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "무선 디버깅에 연결할 수 없습니다. Wi-Fi와 무선 디버깅이 켜져 있는지 확인하세요.",
        "重新配对" to "다시 페어링",
        "特权模式未开启" to "권한 모드 꺼짐",
        "开启后，电脑可以用键盘鼠标操控本机；Oink 可以在后台读取剪贴板并自动广播，控制中心的「粘贴」不用打开 Oink 就能发送文字。" to "켜면 PC에서 키보드와 마우스로 이 기기를 조작하고 백그라운드에서 클립보드를 전송할 수 있습니다.",
        "开始设置" to "설정 시작",
        "会改动的系统设置" to "변경되는 시스템 설정",
        "也可以把手机用 USB 连到电脑，在电脑上的 Oink 中开启。" to "USB로 PC에 연결해 PC용 Oink에서 켤 수도 있습니다.",
        "开发者选项" to "개발자 옵션",
        "需要先开启" to "먼저 켜야 함",
        "无线调试" to "무선 디버깅",
        "用于启动特权服务，需要 Wi‑Fi" to "권한 서비스 시작용 (Wi-Fi 필요)",
        "ADB 授权超时" to "ADB 인증 시간 초과",
        "会被关闭，以便重启后自动恢复" to "자동 복구를 위해 비활성화됩니다",
        "系统没有允许 Oink 使用无线调试。" to "시스템이 무선 디버깅 사용을 허용하지 않았습니다.",
        "无线调试的配对已失效，请重新配对。" to "무선 디버깅 페어링이 만료되었습니다. 다시 페어링하세요.",
        "特权服务没能启动，请重试。" to "권한 서비스를 시작할 수 없습니다. 다시 시도하세요.",
        "无线调试不可用。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "무선 디버깅을 사용할 수 없습니다. Wi-Fi 연결을 확인하세요.",
        "无法连接无线调试。请确认手机已连接 Wi‑Fi，并且「无线调试」已打开。" to "무선 디버깅에 연결할 수 없습니다. Wi-Fi 연결을 확인하세요.",
        "Oink 使用了以上开源项目，感谢它们的作者。" to "Oink는 위 오픈소스 프로젝트를 사용합니다.",
        "无法打开系统设置" to "시스템 설정을 열 수 없습니다",
        "无法重置配对凭据" to "페어링 정보를 재설정할 수 없습니다",
        "分享已中断，请重新分享" to "공유가 중단되었습니다. 다시 시도하세요",
        "特权恢复" to "권한 복구",
        "Oink 正在恢复特权连接" to "권한 연결 복구 중",
        "等待可信无线网络和无线调试服务" to "신뢰할 수 있는 Wi-Fi 및 디버깅 서비스 대기 중",
        "配对码是六位数字，请重新输入。" to "6자리 숫자를 입력하세요.",
        "特权模式配对" to "권한 모드 페어링",
        "六位配对码" to "6자리 코드",
        "刚才的配对码没有成功。请输入「使用配对码配对设备」显示的新配对码，不用离开「设置」。" to "이전 코드가 실패했습니다. 설정을 벗어나지 말고 새 6자리 코드를 입력하세요.",
        "请输入「使用配对码配对设备」显示的六位配对码，不用离开「设置」。" to "설정을 벗어나지 말고 '페어링 코드로 기기 페어링'의 6자리 코드를 입력하세요.",
        "重新输入配对码" to "코드 다시 입력",
        "已找到配对服务" to "페어링 서비스 찾음",
        "正在配对…" to "페어링 중…",
        "正在开启特权模式。" to "권한 모드를 켜는 중입니다.",
        "系统的配对码已失效：离开「设置」时系统会关闭配对码对话框。请在「无线调试」中重新点按「使用配对码配对设备」，不要离开「设置」，在 Oink 的通知里输入新的配对码。" to "설정을 벗어나 코드가 만료되었습니다. 새 코드를 열어 알림에 입력하세요.",
        "配对码不正确或已过期。请在「无线调试」中重新点按「使用配对码配对设备」，在 Oink 的通知里输入新的配对码。" to "코드가 잘못되었거나 만료되었습니다. 새 코드를 열어 알림에 입력하세요.",
        "无法完成配对，请重试。" to "페어링을 완료할 수 없습니다.",

        "粉色" to "핑크",
        "橙色" to "오렌지",
        "紫色" to "퍼플",
        "蓝色" to "블루",
        "青色" to "청록",
        "请先选择要控制的电脑" to "먼저 제어할 PC를 선택하세요",
        "调节" to "조정",
        "全屏" to "전체 화면",
        "收起键盘" to "키보드 접기",
        "弹出键盘" to "키보드 열기",
        "恢复" to "복원",
        "切换键盘：换到模拟键盘（发送原始键码）" to "가상 키보드로 전환",
        "切换键盘：换到系统键盘（手机输入法）" to "시스템 키보드로 전환",
        "选择设备" to "기기 선택",
        "指针未锁定" to "포인터 잠금 해제됨",
        "指针已锁定" to "포인터 잠김",
        "游戏模式已开启" to "게임 모드 켜짐",
        "游戏模式已开启（自动）" to "게임 모드 켜짐 (자동)",
        "更多" to "더 보기",
        "键盘布局" to "키보드 배열",
        "单指划动" to "한 손가락 스와이프",
        "键盘自动弹出" to "키보드 자동 팝업",
        "指针速度" to "포인터 속도",
        "慢" to "느리게",
        "快" to "빠르게",
        "可控制的在线设备" to "제어 가능한 온라인 기기",
        "解锁指针" to "포인터 잠금 해제",
        "锁定指针" to "포인터 잠금",
        "触摸板" to "터치패드",
        "触摸移动" to "터치하여 이동",
        "轻触单击" to "탭하여 클릭",
        "手指已锁定" to "드래그 잠금됨",
        "单击" to "클릭",
        "双击" to "더블 클릭",
        "右键" to "우클릭",
        "释放：抬起锁定的手指" to "해제: 잠긴 손가락 떼기",
        "释放" to "해제",
        "拖动键盘" to "키보드 드래그",
        "已按下" to "눌림",
        "未按下" to "뗌",
        "展开全屏键盘" to "전체 화면 키보드 펼치기",
        "切换扬声器模式" to "스피커 모드 전환",
        "快退 / 上一首" to "되감기 / 이전 곡",
        "播放 / 暂停" to "재생 / 일시 정지",
        "快进 / 下一首" to "빨리 감기 / 다음 곡",
        "音量-" to "음량-",
        "音量+" to "음량+",
        "向前删除" to "앞으로 삭제",
        "还原：回到浮动键盘" to "플로팅 키보드로 복원",
        "退格" to "Backspace",
        "回车" to "Enter",
        "空格" to "Space",
        "左" to "왼쪽",
        "上" to "위",
        "下" to "아래",
        "右" to "오른쪽",
        "按住" to "누름",
        "锁定" to "잠금",
        "解锁" to "잠금 해제",
        "字母" to "문자",
        "符号" to "기호",
        "移动指针" to "포인터 이동",
        "滚动" to "스크롤",
        "美式英语（US）" to "영어 (US)",
        "英式英语（UK）" to "영어 (UK)",
        "德语（QWERTZ）" to "독일어 (QWERTZ)",
        "法语（AZERTY）" to "프랑스어 (AZERTY)",
        "西班牙语" to "스페인어",
        "日语（JIS）" to "일본어 (JIS)",
        "韩语" to "한국어",
        "输入到焦点电脑" to "포커스 PC에 입력",

        // Control Overlay
        "按键映射" to "버튼 매핑",
        "编辑按键" to "버튼 편집",
        "添加按键" to "버튼 추가",
        "配置方案" to "프로필",
        "完成" to "완료",
        "绑定按键" to "키 할당",
        "形状" to "모양",
        "圆形" to "원형",
        "胶囊" to "알약형",
        "大小" to "크기",
        "触发方式" to "동작",
        "按住触发" to "누르고 있기",
        "切换开关" to "토글",
        "文字标签" to "라벨",
        "重命名" to "이름 변경",
        "新建方案" to "새 프로필",
        "透明度" to "불투명도",
        "选择要绑定的键位或鼠标按键" to "할당할 키보드 또는 마우스 버튼 선택",
        "鼠标按键" to "마우스 버튼",
        "常用按键" to "자주 쓰는 키",
        "字母键" to "문자 키",
        "数字键" to "숫자 키",
        "方向键" to "방향 키",
        "功能键" to "기능 키",
        "左键" to "좌클릭",
        "中键" to "가운데 클릭",
        "方案名称" to "프로필 이름",
        "关闭按键映射" to "오버레이 닫기",

        // Pairing errors & Rust bridge
        "请输入有效的 IPv4 地址" to "유효한 IPv4 주소를 입력하세요",
        "不能与当前设备配对" to "이 기기와는 페어링할 수 없습니다",
        "目标主机不可达" to "호스트에 연결할 수 없습니다",
        "连接被拒绝" to "연결이 거부되었습니다",
        "连接超时" to "연결 시간 초과",
        "该地址上没有 Oink 设备" to "이 주소에 Oink 기기가 없습니다",
        "设备身份已变化，请重新搜索" to "기기 ID가 변경되었습니다. 다시 검색하세요",
        "对方的协议版本不兼容" to "프로토콜 버전 호환 불가",
        "对方不支持所需功能" to "필수 기능을 지원하지 않습니다",
        "设备正忙，请稍后重试" to "기기가 사용 중입니다",
        "操作过于频繁，请稍后重试" to "요청이 너무 많습니다. 잠시 후 다시 시도하세요",
        "对方拒绝了配对" to "상대방이 페어링을 거절했습니다",
        "对方取消了配对" to "상대방이 페어링을 취소했습니다",
        "已取消配对" to "페어링 취소됨",
        "对方关闭了配对" to "상대방이 페어링을 닫았습니다",
        "连接已断开" to "연결이 끊어졌습니다",
        "请输入六位配对码" to "6자리 코드를 입력하세요",
        "配对码不正确，请重新输入" to "코드가 올바르지 않습니다",
        "尝试次数已用完" to "시도 횟수 초과",
        "此配对已结束" to "페어링이 종료되었습니다",
        "设备已经连接" to "이미 연결되어 있습니다",
        "已由另一配对会话接替" to "다른 페어링 세션으로 대체됨",
        "设备组已变化" to "기기 그룹이 변경되었습니다",
        "设备数量已达上限" to "기기 수 제한에 도달했습니다",
        "设备组数据超过限制" to "그룹 데이터 제한 초과",
        "无法验证设备组成员" to "그룹 구성원을 확인할 수 없습니다",
        "设备组合并冲突" to "그룹 병합 충돌",
        "无法保存配对结果" to "페어링 결과를 저장할 수 없습니다",
        "无法确认提交结果" to "커밋 결과를 확인할 수 없습니다",
        "无法获取合并证明" to "병합 증명을 가져올 수 없습니다",
        "设备成员资格已撤销" to "구성원 자격이 취소됨",
        "设备组已变化，无法撤销" to "그룹 변경으로 취소할 수 없습니다",
        "不允许此操作" to "허용되지 않는 작업입니다",
        "搜索结果的连接已断开，请重新搜索" to "검색된 연결이 끊어졌습니다. 다시 검색하세요",
        "配对状态已变化，请重试" to "페어링 상태가 변경되었습니다",
        "配对协议异常" to "페어링 프로토콜 오류",
        "设备状态已变化，请重新搜索" to "기기 상태가 변경되었습니다",
        "配对已经提交" to "페어링이 이미 확정됨",
        "无法验证设备身份" to "기기를 인증할 수 없습니다",
        "没有共同支持的加密方式" to "공통 암호화 방식이 없습니다",
        "不支持该屏幕排列" to "지원되지 않는 화면 배치입니다",
        "配对服务不可用" to "페어링 서비스를 사용할 수 없습니다",
        "网络操作超时" to "네트워크 작업 시간 초과",
        "对方还有没处理的配对请求，请稍后重试" to "상대방에 대기 중인 요청이 있습니다",
        "配对状态未知" to "알 수 없는 페어링 상태",
        "本机没有连接网络" to "네트워크에 연결되어 있지 않습니다",
        "核心事件处理失败" to "코어 이벤트 처리 실패",
        "设置已生效" to "설정 적용됨",
        "设置未变化" to "변경 사항 없음",
        "设置等待确认" to "확인 대기 중",
        "地址查询已失效，请重新查找" to "주소 조회가 만료되었습니다",
        "配对已完成" to "페어링 완료",
        "配对提交结果待确认，请检查设备列表" to "확인 대기 중, 기기 목록을 확인하세요",
        "未知设置" to "알 수 없는 설정",
        "无法识别图片格式" to "이미지 형식을 인식할 수 없음",
        "图片太大，无法复制" to "이미지가 너무 커서 복사할 수 없음",
        "正在连接对方…" to "상대 기기에 연결 중…",
        "正在验证并合并设备组…" to "확인 및 그룹 병합 중…",
        "等待对方接受并输入验证码" to "상대방의 수락 및 코드 입력 대기 중",
        "请输入对方显示的六位码" to "상대방 화면의 6자리 코드 입력",
        "对方的状态已变化，请重试" to "상대방 상태가 변경됨, 다시 시도하세요",
        "设置值无效" to "잘못된 설정 값",
        "对方无法保存设置" to "상대방이 설정을 저장할 수 없음",
        "对方无法应用设置" to "상대방이 설정을 적용할 수 없음",
        "不确定是否已生效，请检查对方状态" to "적용 여부를 확인하세요",
        "该设备不是音频出口" to "이 기기는 오디오 출력이 아닙니다",
        "音频线路已变化，请重试" to "오디오 경로가 변경되었습니다",
        "音频输出设备已变化，请重试" to "오디오 출력 기기가 변경되었습니다",
        "对方没有可用的音频输出" to "사용 가능한 오디오 출력이 없음",
        "对方不支持调节音量" to "음량 조절 미지원",
        "对方没有授予所需权限" to "필요한 권한이 없습니다",
        "已被新的设置取代" to "새 설정으로 대체됨",
        "对方的状态有冲突，请重试" to "상태 충돌, 다시 시도하세요",
        "未知原因" to "알 수 없는 원인",
        "扬声器转发音量" to "스피커 전달 음량",
        "麦克风转发音量" to "마이크 전달 음량",
        "恢复此设备" to "이 기기 재개",
        "暂停此设备" to "이 기기 일시 정지",
        "共享屏幕" to "화면 공유",
        "屏幕仅本机" to "화면 로컬만",
        "共享鼠标" to "마우스 공유",
        "鼠标仅本机" to "마우스 로컬만",
        "禁用鼠标" to "마우스 비활성화",
        "共享键盘" to "키보드 공유",
        "键盘仅本机" to "키보드 로컬만",
        "禁用键盘" to "키보드 비활성화",
        "启用键盘" to "키보드 활성화",
        "剪贴板仅本机" to "클립보드 로컬만",
        "启用音频转发" to "오디오 전달 켜기",
        "关闭音频转发" to "오디오 전달 끄기",
        "取消音频出口" to "출력 기기 해제",
        "设为音频出口" to "출력 기기로 설정",
        "麦克风仅本机" to "마이크 로컬만",
        "接收麦克风" to "마이크 수신",
        "游戏模式自动" to "게임 모드 자동",
        "开启游戏模式" to "게임 모드 켜기",
        "关闭游戏模式" to "게임 모드 끄기",
        "解除指针锁定" to "포인터 잠금 해제",
        "锁定指针到此设备" to "여기에 포인터 잠금",
        "屏幕" to "화면",
        "输出音量" to "출력 음량",
        "设置被拒绝" to "설정이 거부됨",
        "设置失败" to "설정 실패",
        "对端缺少能力" to "상대방에게 필요한 기능이 없음",
        "对端协议不兼容" to "프로토콜이 호환되지 않음",
        "无法保存到相册" to "사진에 저장할 수 없음",
        "停止设备广播失败" to "기기 알림 중지 실패",
        "局域网发现失败" to "LAN 검색 실패",
        "停止局域网发现失败" to "LAN 검색 중지 실패",
        "解析附近设备失败" to "주변 기기 확인 실패",
        "订阅附近设备失败" to "주변 기기 구독 실패",
        "无法分享" to "공유할 수 없음",
        "对方设备" to "상대방 기기",
        "对方不在线" to "상대방이 오프라인입니다",
        "所有在线设备" to "온라인인 모든 기기",
        "此内容请选一台电脑" to "이 항목은 PC를 선택하세요",
        "没有其它设备在线" to "온라인인 다른 기기가 없습니다",
        "下一项" to "다음 항목",
        "上一项" to "이전 항목",
        "展开" to "펼치기",
        "文字" to "텍스트",
        "音频转发" to "오디오 전달",
        "分享内容为空或不受支持" to "공유 내용이 비어 있거나 지원되지 않습니다",
        "只能读取获授权的内容 URI" to "권한이 있는 콘텐츠 URI만 읽을 수 있습니다",
        "无法创建暂存目录" to "임시 폴더를 만들 수 없습니다",
        "无法保存照片" to "사진을 저장할 수 없습니다",
        "无法读取文件夹" to "폴더를 읽을 수 없습니다",
        "文件夹内容还在加载，请稍后重新选择" to "폴더를 불러오는 중입니다. 잠시 후 다시 선택하세요",
        "还没有下载完成" to "아직 다운로드가 완료되지 않았습니다",
        "无法打开" to "열 수 없음",
        "此设备未提供状态" to "이 기기에서 상태를 제공하지 않음",
    )
}
