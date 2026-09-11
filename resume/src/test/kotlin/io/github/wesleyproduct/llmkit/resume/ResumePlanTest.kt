package io.github.wesleyproduct.llmkit.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResumePlanTest {

    /** 이 코드가 있는 이유. 모델은 1~3.5GB 라 이미 받은 바이트가 재시도에서 살아남아야 한다. */
    @Test
    fun `206 이면 디스크에 있던 지점부터 이어받는다`() {
        val plan = ResumePlan.of(existingBytes = 1_500_000_000, responseCode = 206, contentLength = 500_000_000)

        assertTrue(plan.append)
        assertEquals(1_500_000_000L, plan.startAt)
    }

    /**
     * 206 은 **남은 양만** 알려준다. 그걸 전체로 쓰면 거의 다 받은 다운로드가 0퍼센트 근처로
     * 돌아가고, 그건 사용자 눈에 "처음부터 다시 받는다"로 보인다 — 이어받기로 고치려던 바로 그것이다.
     */
    @Test
    fun `206 이면 이미 받은 바이트를 전체 크기에 도로 더한다`() {
        val plan = ResumePlan.of(existingBytes = 1_500_000_000, responseCode = 206, contentLength = 500_000_000)

        assertEquals(2_000_000_000L, plan.total)
    }

    /**
     * 비싼 실수. 범위를 요청했다고 범위를 받는 게 아니다 — CDN 이 헤더를 무시할 수도, 리다이렉트에서
     * 빠질 수도 있고, 그러면 본문은 첫 바이트부터 온다. 그걸 조각 파일 뒤에 붙이면 크기는 맞고
     * 내용은 깨진 것이 나온다. 로드까지 되고 나서 한참 뒤에 실패하며, 여기를 가리키는 단서가 없다.
     */
    @Test
    fun `200 이면 조각 파일이 있어도 처음부터 다시 받는다`() {
        val plan = ResumePlan.of(existingBytes = 1_500_000_000, responseCode = 200, contentLength = 2_000_000_000)

        assertFalse(plan.append, "200 은 처음부터 보낸다 - 이어붙이면 파일이 깨진다")
        assertEquals(0L, plan.startAt)
        assertEquals(2_000_000_000L, plan.total)
    }

    /** 디스크에 아무것도 없으면 평범한 첫 시도다. */
    @Test
    fun `조각 파일이 없으면 그냥 처음부터 받는다`() {
        val plan = ResumePlan.of(existingBytes = 0, responseCode = 200, contentLength = 2_000_000_000)

        assertFalse(plan.append)
        assertEquals(0L, plan.startAt)
    }

    /** 받아둔 것도 없는데 206 이 오는 것은 앞뒤가 안 맞는다. 믿지 말고 새로 받는다. */
    @Test
    fun `받아둔 것이 없는데 온 206 은 이어받기로 치지 않는다`() {
        val plan = ResumePlan.of(existingBytes = 0, responseCode = 206, contentLength = 2_000_000_000)

        assertFalse(plan.append)
        assertEquals(0L, plan.startAt)
    }

    /** 청크 응답에는 Content-Length 가 없다. 모르는 크기는 0 이 아니다 — 진행률만 못 낼 뿐 복사는 돌아야 한다. */
    @Test
    fun `크기를 모르면 전체를 null 로 둔다`() {
        val plan = ResumePlan.of(existingBytes = 0, responseCode = 200, contentLength = -1)

        assertEquals(null, plan.total)
    }

    @Test
    fun `크기를 몰라도 이어받기면 받아둔 바이트는 지킨다`() {
        val plan = ResumePlan.of(existingBytes = 1_000, responseCode = 206, contentLength = -1)

        assertTrue(plan.append)
        assertEquals(1_000L, plan.startAt)
        assertEquals(null, plan.total)
    }
}
