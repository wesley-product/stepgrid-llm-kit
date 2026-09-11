package io.github.wesleyproduct.llmkit.resume

import java.net.HttpURLConnection

/**
 * 받다 만 파일을 이어받아도 되는지를, **요청한 것이 아니라 서버가 실제로 답한 것**으로 판단한다.
 *
 * 범위를 요청하는 것과 범위를 받는 것은 다르다. CDN 이 `Range` 를 무시할 수 있고 리다이렉트를
 * 거치며 헤더가 빠질 수도 있는데, 그 경우 응답은 첫 바이트부터 시작하는 평범한 200 이다.
 * 그걸 디스크에 있던 조각 뒤에 이어 붙이면 **크기는 맞고 내용은 깨진 파일**이 된다 — 2GB 짜리
 * 모델이 로드까지 되고 나서 런타임 깊은 곳에서 실패하는, 여기를 되짚어 올 실마리가 없는 실패다.
 *
 * 틀렸을 때의 대가가 크고 완전히 조용해서, 복사 루프 안에 인라인으로 두지 않고 이름을 붙여
 * 밖으로 꺼내고 테스트를 둘렀다.
 *
 * ```
 * val existing = target.length()
 * val conn = (URL(url).openConnection() as HttpURLConnection).apply {
 *     if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
 * }
 * val plan = ResumePlan.of(existing, conn.responseCode, conn.contentLengthLong)
 *
 * FileOutputStream(target, plan.append).use { out ->
 *     conn.inputStream.copyTo(out)   // plan.startAt 부터 이어지거나, 처음부터 다시 쓴다
 * }
 * ```
 *
 * @property append 조각 파일을 지우지 말고 **이어서 쓸 것인가**
 * @property startAt 이미 디스크에 있어 진행률에 포함해야 하는 바이트 수
 * @property total 전체 크기. 알 수 없으면 `null` — 206 응답은 **남은 양만** 알려주므로
 *   이미 받아둔 바이트를 도로 더해야 퍼센트가 바닥으로 떨어지지 않는다
 */
public data class ResumePlan(
    val append: Boolean,
    val startAt: Long,
    val total: Long?,
) {
    public companion object {
        /**
         * @param existingBytes 조각 파일의 크기. 없으면 0
         * @param responseCode 서버가 답한 상태 코드
         * @param contentLength `Content-Length`. **206 이면 남은 바이트, 200 이면 전체 크기**다.
         *   청크 응답 등으로 모를 때는 음수나 0 을 넘기면 된다
         */
        public fun of(existingBytes: Long, responseCode: Int, contentLength: Long): ResumePlan {
            val body = contentLength.takeIf { it > 0 }
            // 206 만이 우리가 요청한 지점부터 보낸다고 약속한다. 나머지는 전부 처음부터다.
            val resuming = existingBytes > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
            return if (resuming) {
                ResumePlan(append = true, startAt = existingBytes, total = body?.plus(existingBytes))
            } else {
                ResumePlan(append = false, startAt = 0L, total = body)
            }
        }
    }
}
